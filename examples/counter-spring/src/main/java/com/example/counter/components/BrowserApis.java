package com.example.counter.components;

import static j2act.html.TagCreator.*;

import j2act.BrowserException;
import j2act.ComponentTag;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * window() (ADR 0022): the browser's own APIs from Java, mirrored from WebIDL. Calls from
 * a handler run after the patch, in order; the copy runs inside the click, so the
 * clipboard's user-activation rule holds.
 */
public final class BrowserApis extends ComponentTag {

  private final State<String> stored = state("nothing");
  private final State<String> title = state("unknown");
  private final State<String> place = state("unknown");
  private final State<String> copied = state("not yet");
  private final State<String> failure = state("none");

  public static BrowserApis browserApis() {
    return new BrowserApis();
  }

  @Override protected DivTag render() {
    return div(
      button("Store and read back")
        .withId("api-store")
        .onClick(e -> {
          // Same queue, same order: getItem reads what setItem just wrote.
          window().localStorage().setItem("j2act-demo", "kept in localStorage");
          window().localStorage().getItem("j2act-demo").thenAccept(stored::set);
        }),
      button("Read the title")
        .withId("api-title")
        .onClick(e -> window().document().title().thenAccept(title::set)),
      button("Focus the field")
        .withId("api-focus")
        .onClick(e -> window().document().getElementById("api-input").focus()),
      input()
        .withId("api-input")
        .withPlaceholder("focused from Java"),
      button("Where am I")
        .withId("api-where")
        .onClick(e -> window().navigator().geolocation().getCurrentPosition().whenComplete((position, error) ->
          place.set(error == null
            ? position.coords().latitude() + ", " + position.coords().longitude()
            : "refused: " + ((BrowserException) error).name()))),
      button("Copy")
        .withId("api-copy")
        .onClick(() -> window().navigator().clipboard().writeText("copied by j2act"), done -> copied.set("copied")),
      button("Call a missing element")
        .withId("api-missing")
        .onClick(e -> window().document().getElementById("nope").focus().whenComplete((done, error) ->
          failure.set(error == null ? "no error" : ((BrowserException) error).name()))),
      p("stored: " + stored.get())
        .withId("api-stored"),
      p("title: " + title.get())
        .withId("api-title-out"),
      p("place: " + place.get())
        .withId("api-place"),
      p("clipboard: " + copied.get())
        .withId("api-copied"),
      p("failure: " + failure.get())
        .withId("api-failure")
    );
  }
}
