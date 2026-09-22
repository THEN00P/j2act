package com.example.counter.components;

import static j2act.html.TagCreator.*;

import javax.inject.Inject;

import com.example.counter.services.Directory;
import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.Prop;
import j2act.Query;

/** Remounted by an if on the page; the query result survives unmount at the same slot (ADR 0020). */
public final class ProfileCard extends ComponentTag {

  @Inject private Directory directory;

  private final Prop<String> name = prop();
  private final Query<String> profile = query(() -> directory.profile(name.get()));

  public static ProfileCard profileCard() {
    return new ProfileCard();
  }

  public ProfileCard withName(String n) {
    name.set(n);
    return this;
  }

  @Override protected ContainerTag render() {
    return div(
      profile.isPending()
        ? p("Loading profile…")
        : p(profile.get()),
      small("directory calls so far: " + directory.calls())
    ).withClass("card");
  }
}
