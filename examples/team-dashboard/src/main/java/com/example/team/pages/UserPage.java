package com.example.team.pages;

import static j2act.Routes.*;
import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import jakarta.inject.Inject;

import com.example.team.db.Users;
import j2act.LiveComponent;
import j2act.Page;
import j2act.Query;
import j2act.html.tags.HtmlTag;

/**
 * /admin/users/{id}. A missing user throws notFound() from the loader: a real
 * 404 with the fallback page on a full serve, a soft navigation over the socket.
 * Same instance is kept across /users/1 -> /users/2; the loader read
 * pathParam("id"), so it refetches. Rename is a form submit whose field is
 * controlled: the render sets its value to the current name.
 */
public class UserPage extends LiveComponent implements Page {

  @Inject private Users users;

  private final Query<Users.User> user = query(() -> users
    .find(Long.parseLong(pathParam("id")))
    .orElseThrow(() -> notFound()));

  @Override public HtmlTag render() {
    return html(
      head(
        title(user.get().getName())
      ),
      body(
        div(
          h1(user.get().getName()),
          p(user.get().getEmail()),
          form(
            label(
              text("Rename "),
              input()
                .withName("name")
                .withValue(user.get().getName())
            ),
            UI.button("Save")
              .withType("submit")
              .withPending(spinner())
          )
            .onSubmit(e -> {
              users.rename(user.get().getId(), e.value("name").trim());
              user.refetch();
            }),
          a("Back to users")
            .withHref("/admin")
        )
      )
    );
  }
}
