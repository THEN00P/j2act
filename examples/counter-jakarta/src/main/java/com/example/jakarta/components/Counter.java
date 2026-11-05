package com.example.jakarta.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.View;
import j2act.ComponentTag;
import j2act.Prop;
import j2act.Signal;
import j2act.html.tags.ButtonTag;

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

  @Override protected View setup() {
    var count = createSignal(0);
    return () -> button(label.get() + ": " + count.get())
      .onClick(e -> count.set(count.get() + 1));
  }
}
