package com.example.team.components;

import static j2act.Preload.INTENT;
import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import javax.inject.Inject;

import com.example.team.db.Users;
import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.State;
import j2act.Variant;

/**
 * Stateful row: the delete confirmation is per-row State, so callers must
 * withKey(...) it or a reorder would move "confirm?" onto the wrong user.
 * Delete branch is a plain ternary over AuthCtx, re-checked per event.
 */
public final class UserRow {

  private UserRow() {}

  public static UserRowTag userRow() {
    return new UserRowTag();
  }

  public static final class UserRowTag extends ComponentTag {
    @Inject private Users users;

    private Users.User user;
    private final State<Boolean> confirming = state(false);

    public UserRowTag withUser(Users.User user) {
      this.user = user;
      return this;
    }

    @Override protected ContainerTag render() {
      return tr(
        td(
          a(user.getName())
            .withHref("/admin/users/" + user.getId())
            .withPreload(INTENT)
        ),
        td(user.getEmail()),
        td(
          auth().hasRole("superadmin")
            ? deleteCell()
            : null
        )
      );
    }

    private ContainerTag deleteCell() {
      return confirming.get()
        ? span(
            UI.button("Confirm delete")
              .withVariant(Variant.DESTRUCTIVE)
              .withPending(spinner())
              .onClick(e -> users.delete(user.getId())),
            UI.button("Cancel")
              .onClick(e -> confirming.set(false))
          )
        : UI.button("Delete")
            .withVariant(Variant.DESTRUCTIVE)
            .onClick(e -> confirming.set(true));
    }
  }
}
