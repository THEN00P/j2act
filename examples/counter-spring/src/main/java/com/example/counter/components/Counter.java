package com.example.counter.components;

import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.Prop;
import j2act.State;

/** Stateful counter; two counter() siblings get separate tree slots and State (ADR 0019). */
public final class Counter extends ComponentTag {

  private final Prop<String> label = prop("Clicked");

  public static Counter counter() {
    return new Counter();
  }

  public Counter withLabel(String l) {
    label.set(l);
    return this;
  }

  @Override protected ContainerTag render() {
    State<Integer> count = state(0);
    return button(label.get() + ": " + count.get())
      .withClass("counter")
      .onClick(e -> count.set(count.get() + 1));
  }
}
