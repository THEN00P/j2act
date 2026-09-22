package com.example.team.components;

import static j2act.Preload.INTENT;
import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import javax.inject.Inject;

import com.example.team.db.Users;
import j2act.ComponentTag;
import j2act.DomContent;
import j2act.Prop;
import j2act.State;
import j2act.Variant;
import j2act.html.tags.TrTag;

/**
 * Stateful row: the delete confirmation is per-row State, so callers must
 * withKey(...) it or a reorder would move "confirm?" onto the wrong user.
 * Delete branch is a plain ternary over AuthCtx, re-checked per event.
 */
public final class UserRow extends ComponentTag {

  @Inject private Users users;

  private final Prop<Users.User> user = prop();
  private final State<Boolean> confirming = state(false);

  public static UserRow userRow() {
    return new UserRow();
  }

  public UserRow withUser(Users.User u) {
    user.set(u);
    return this;
  }

  @Override protected TrTag render() {
    return tr(
      td(
        a(user.get().getName())
          .withHref("/admin/users/" + user.get().getId())
          .withPreload(INTENT)
      ),
      td(user.get().getEmail()),
      td(
        auth().hasRole("superadmin")
          ? deleteCell()
          : null
      )
    );
  }

  private DomContent deleteCell() {
    return confirming.get()
      ? span(
          UI.button("Confirm delete")
            .withVariant(Variant.DESTRUCTIVE)
            .withPending(spinner())
            .onClick(e -> users.delete(user.get().getId())),
          UI.button("Cancel")
            .onClick(e -> confirming.set(false))
        )
      : UI.button("Delete")
          .withVariant(Variant.DESTRUCTIVE)
          .onClick(e -> confirming.set(true));
  }
}
