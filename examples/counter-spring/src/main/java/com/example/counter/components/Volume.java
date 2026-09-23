package com.example.counter.components;

import static j2act.html.TagCreator.*;

import java.time.Duration;

import j2act.ComponentTag;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * withThrottle (ADR 0013): dragging sends the first value at once, then at most one every
 * 250 ms, and always the value the slider stopped on.
 */
public final class Volume extends ComponentTag {

  private final State<String> level = state("50");
  private final State<Integer> received = state(0);

  public static Volume volume() {
    return new Volume();
  }

  @Override protected DivTag render() {
    return div(
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
