package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;

import com.example.counter.services.Directory;
import j2act.View;
import j2act.ComponentTag;
import j2act.html.tags.DivTag;
import j2act.Prop;

/** Remounted by an if on the page: its load lives as long as its slot, so showing it again loads again (ADR 0031). */
public final class ProfileCard extends ComponentTag {

  @Inject private Directory directory;

  private final Prop<String> name = prop();

  public static ProfileCard profileCard() {
    return new ProfileCard();
  }

  public ProfileCard withName(String n) {
    name.set(n);
    return this;
  }

  @Override protected View setup() {
    var profile = createMemo(() -> async(() -> directory.profile(name.get())));
    return () -> div(
      loading(() -> div(
        p(profile.get()),
        small("directory calls so far: " + directory.calls())
      )).withFallback(p("Loading profile…"))
    ).withClass("card");
  }
}
