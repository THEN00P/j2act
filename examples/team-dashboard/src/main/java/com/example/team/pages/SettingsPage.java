package com.example.team.pages;

import static com.example.team.components.AvatarUploader.avatarUploader;
import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import jakarta.inject.Inject;

import com.example.team.db.Users;
import com.example.team.stores.AppStores;
import j2act.LiveComponent;
import j2act.Page;
import j2act.State;
import j2act.html.tags.HtmlTag;

/**
 * A plain form, no form library (deferred by cut). The input is uncontrolled:
 * the render never sets its value, so re-renders keep what the user typed, and
 * the submit carries it as a field (ADR 0013). The Save button swaps to a
 * spinner client-side until the handler's morph lands.
 */
public class SettingsPage extends LiveComponent implements Page {

  @Inject private Users users;

  private final State<String> saved = state(null);

  @Override public HtmlTag render() {
    return html(
      head(
        title("Settings")
      ),
      body(
        div(
          h1("Settings"),
          form(
            label(
              text("Display name "),
              input()
                .withName("displayName")
                .isRequired()
            ),
            UI.button("Save")
              .withType("submit")
              .withPending(spinner())
          )
            .onSubmit(e -> {
              String name = e.value("displayName").trim();
              users.rename(AppStores.currentUser.select(u -> u.userId), name);
              saved.set(name);
            }),
          saved.get() == null
            ? null
            : p("Saved: " + saved.get()),
          h2("Avatar"),
          avatarUploader()
        )
      )
    );
  }
}
