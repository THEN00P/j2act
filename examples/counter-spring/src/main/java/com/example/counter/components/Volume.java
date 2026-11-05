package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.time.Duration;

import j2act.View;
import j2act.ComponentTag;
import j2act.Signal;
import j2act.html.tags.DivTag;

/**
 * withThrottle (ADR 0013): dragging sends the first value at once, then at most one every
 * 250 ms, and always the value the slider stopped on.
 */
public final class Volume extends ComponentTag {

  public static Volume volume() {
    return new Volume();
  }

  @Override protected View setup() {
    var level = createSignal("50");
    var received = createSignal(0);
    return () -> div(
      input()
        .withId("volume")
        .withType("range")
        .withMin("0")
        .withMax("100")
        .withValue(level.get())
        .withThrottle(Duration.ofMillis(250))
        .onInput(e -> {
          level.set(e.value());
          received.set(received.get() + 1);
        }),
      span(" volume " + level.get() + " after " + received.get() + " events").withId("volume-status")
    );
  }
}
