package com.example.team.pages;

import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import javax.inject.Inject;

import com.example.team.components.AvatarUploader;
import com.example.team.db.Users;
import com.example.team.stores.AppStores;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Page;
import j2act.State;

/**
 * Plain signals + button callback form. No form library (deferred by cut).
 * The Save button swaps to a spinner client-side until the handler's morph lands.
 */
public class SettingsPage extends LiveComponent implements Page {

  @Inject private Users users;

  @Override public ContainerTag render() {
    State<String> displayName = state("");
    State<String> saved = state(null);

    return html(
      head(
        title("Settings")
      ),
      body(
        div(
          h1("Settings"),
          label("Display name"),
          input()
            .withValue(displayName.get())
            .onChange(e -> displayName.set(e.value())),
          UI.button("Save")
            .withPending(spinner())
            .onClick(e -> {
              users.rename(AppStores.currentUser.select(u -> u.userId), displayName.get());
              saved.set(displayName.get());
            }),
          saved.get() == null
            ? null
            : p("Saved: " + saved.get()),
          h2("Avatar"),
          AvatarUploader.avatarUploader()
        )
      )
    );
  }
}
