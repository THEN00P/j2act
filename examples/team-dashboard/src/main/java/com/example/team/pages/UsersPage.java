package com.example.team.pages;

import static com.example.team.components.UserRow.userRow;
import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;

import javax.inject.Inject;

import com.example.team.db.Users;
import j2act.LiveComponent;
import j2act.Mutation;
import j2act.Page;
import j2act.PageError;
import j2act.Query;
import j2act.State;
import j2act.html.tags.HtmlTag;

/**
 * Filterable directory. Eager filter via onInput plus a client-side debounce.
 * The query is keyless: filter is read inside the loader, so it is the
 * dependency. SSR awaits the users query, so the first paint already has
 * rows. CSV export streams through download(), and the button spins until
 * the last byte.
 */
public class UsersPage extends LiveComponent implements Page {

  @Inject private Users users;

  private final State<String> filter = state("");
  private final Query<List<Users.User>> list = query(() -> users.search("%" + filter.get() + "%"));

  @Override public HtmlTag render() {
    Mutation<String> export = download((String like, OutputStream out) -> users.writeCsv(like, out))
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
            .withType("search")
            .withPlaceholder("Filter by name")
            .withValue(filter.get())
            .withDebounce(Duration.ofMillis(250))
            .onInput(e -> filter.set(e.value())),
          UI.button("Export CSV")
            .withPending(spinner())
            .onClick(e -> export.mutate("%" + filter.get() + "%")),
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
              each(list.get(), u -> userRow()
                .withUser(u)
                .withKey(u.getId()))
            )
          )
        )
      )
    );
  }

  @Override public HtmlTag loading() {
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

  @Override public HtmlTag error(PageError e) {
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
