package com.example.team.pages;

import static j2act.Routes.*;
import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import javax.inject.Inject;

import com.example.team.db.Users;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Page;
import j2act.Query;
import j2act.State;

/**
 * /admin/users/{id}. A missing user throws notFound() from the loader: a real
 * 404 with the fallback page on a full serve, a soft navigation over the socket.
 * Same instance is kept across /users/1 -> /users/2; the query refetches by key.
 */
public class UserPage extends LiveComponent implements Page {

  @Inject private Users users;

  @Override public ContainerTag render() {
    long id = Long.parseLong(pathParam("id"));
    Query<Users.User> user = query(
      () -> "user:" + id,
      () -> users.find(id).orElseThrow(() -> notFound()));
    State<String> name = state("");

    return html(
      head(
        title(user.get().getName())
      ),
      body(
        div(
          h1(user.get().getName()),
          p(user.get().getEmail()),
          label("Rename"),
          input()
            .withValue(name.get())
            .onChange(e -> name.set(e.value())),
          UI.button("Save")
            .withPending(spinner())
            .onClick(e -> {
              users.rename(id, name.get());
              user.refetch();
            }),
          a("Back to users")
            .withHref("/admin")
        )
      )
    );
  }
}
