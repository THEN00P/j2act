package com.example.team.pages;

import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import java.time.Duration;
import java.util.List;

import javax.inject.Inject;

import com.example.team.components.UserRow;
import com.example.team.db.Users;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Mutation;
import j2act.Page;
import j2act.PageError;
import j2act.Query;
import j2act.State;

/**
 * Filterable directory. Eager filter via onInput plus a client-side debounce;
 * SSR awaits the users query, so the first paint already has rows. CSV export
 * streams through download(), and the button spins until the last byte.
 */
public class UsersPage extends LiveComponent implements Page {

  @Inject private Users users;

  @Override public ContainerTag render() {
    State<String> filter = state("");
    Query<List<Users.User>> list = query(
      () -> "users:" + filter.get(),
      () -> users.search("%" + filter.get() + "%"));
    Mutation<Void> export = download(out -> users.writeCsv("%" + filter.get() + "%", out))
      .withFileName("users.csv")
      .withContentType("text/csv");

    return html(
      head(
        title("Users" + (filter.get().isEmpty() ? "" : " — " + filter.get())),
        meta()
          .withName("description")
          .withContent("Team member directory")
      ),
      body(
        div(
          h1("Users"),
          input()
            .withPlaceholder("Filter by name")
            .withValue(filter.get())
            .withDebounce(Duration.ofMillis(250))
            .onInput(e -> filter.set(e.value())),
          UI.button("Export CSV")
            .withPending(spinner())
            .onClick(e -> export.mutate()),
          export.error().get() == null
            ? null
            : p("Export failed: " + export.error().get()),
          table(
            thead(
              tr(
                th("Name"),
                th("Email"),
                th("")
              )
            ),
            tbody(
              each(list.get(), u -> UserRow.userRow()
                .withUser(u)
                .withKey(u.getId()))
            )
          )
        )
      )
    );
  }

  @Override public ContainerTag loading() {
    return html(
      head(
        title("Users…")
      ),
      body(
        div("Loading…")
          .withClass("skeleton")
      )
    );
  }

  @Override public ContainerTag error(PageError e) {
    return html(
      head(
        title("Users — error")
      ),
      body(
        div(
          p("Failed: " + e.message()),
          button("Retry")
            .onClick(ev -> e.retry())
        )
      )
    );
  }
}
