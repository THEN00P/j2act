package com.example.team.pages;

import static j2act.html.TagCreator.*;

import com.example.team.components.AvatarUploader;
import com.example.team.stores.AppStores;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Page;
import j2act.State;

/** Plain signals + button callback form. No form library (deferred by cut). */
public class SettingsPage extends LiveComponent implements Page {

  @Override public ContainerTag render() {
    State<String> displayName = state("");
    State<String> saved = state(null);

    return html(
      head(title("Settings")),
      body(div(
        h1("Settings"),
        label("Display name"),
        input().withValue(displayName.get())
          .onChange(e -> displayName.set(e.value())),
        button("Save").onClick(e -> {
          saveDisplayName(displayName.get());
          saved.set(displayName.get());
        }),
        saved.get() == null ? null : p("Saved: " + saved.get()),
        h2("Avatar"),
        AvatarUploader.avatarUploader()
      ))
    );
  }

  private void saveDisplayName(String name) {
    em().createQuery("update User u set u.displayName = :n where u.id = :id")
      .setParameter("n", name).setParameter("id", AppStores.currentUser.select(u -> u.userId)).executeUpdate();
  }
}
