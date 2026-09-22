package com.example.team.pages;

import static j2act.html.TagCreator.*;

import java.util.List;

import com.example.team.components.UserRow;
import com.example.team.db.Users;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Page;
import j2act.PageError;
import j2act.Query;
import j2act.State;

/** Filterable directory. Lazy filter commits on blur — no DB spam, no debounce needed. */
public class UsersPage extends LiveComponent implements Page {

  @Override public ContainerTag render() {
    State<String> filter = state("");
    Query<List<Users.User>> users = query(
      () -> "users:" + filter.get(),
      () -> Users.search(em(), "%" + filter.get() + "%"));

    return html(
      head(
        title("Users" + (filter.get().isEmpty() ? "" : " — " + filter.get())),
        meta().withName("description").withContent("Team member directory")),
      body(div(
        h1("Users"),
        input()
          .withPlaceholder("Filter by name")
          .withValue(filter.get())
          .onChange(e -> filter.set(e.value())),
        table(
          thead(tr(th("Name"), th("Email"), th(""))),
          tbody(each(users.get(), u -> UserRow.userRow().withUser(u))))
      ))
    );
  }

  @Override public ContainerTag loading() {
    return html(
      head(title("Users…")),
      body(div("Loading…").withClass("skeleton")));
  }

  @Override public ContainerTag error(PageError e) {
    return html(
      head(title("Users — error")),
      body(div(
        p("Failed: " + e.message()),
        button("Retry").onClick(ev -> e.retry()))));
  }
}
