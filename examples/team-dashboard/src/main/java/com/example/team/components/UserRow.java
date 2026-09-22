package com.example.team.components;

import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import com.example.team.db.Users;
import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.Variant;

/**
 * Stateless row. User arrives via with*; delete branch is a plain ternary over
 * AuthCtx — hidden UI plus automatic per-event re-check, no require() calls.
 * No hardcoded withId: reusable components must not emit page-wide ids.
 */
public final class UserRow {

  private UserRow() {}

  public static UserRowTag userRow() {
    return new UserRowTag();
  }

  public static final class UserRowTag extends ComponentTag {
    private Users.User user;

    public UserRowTag withUser(Users.User user) {
      this.user = user;
      return this;
    }

    @Override protected ContainerTag render() {
      return tr(
        td(user.getName()),
        td(user.getEmail()),
        td(auth().hasRole("superadmin")
          ? UI.button("Delete").withVariant(Variant.DESTRUCTIVE)
              .onClick(e -> Users.delete(em(), user.getId()))
          : null)
      );
    }
  }
}
