package com.example.team.components;

import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import j2act.ComponentTag;
import j2act.Prop;
import j2act.State;
import j2act.Variant;
import j2act.html.tags.DivTag;

/**
 * Stateful counter. Factory takes children only; label arrives via with*.
 * State binds to the tree slot (ADR 0019): two siblings never share state
 * or morph targets.
 */
public final class Counter extends ComponentTag {

  private final Prop<String> label = prop("Clicked");

  public static Counter counter() {
    return new Counter();
  }

  public Counter withLabel(String l) {
    label.set(l);
    return this;
  }

  @Override protected DivTag render() {
    State<Integer> count = state(0);
    return div(
      UI.button(label.get() + " " + count.get() + " times")
        .withVariant(Variant.PRIMARY)
        .onClick(e -> count.set(count.get() + 1)),
      count.get() > 5
        ? p("warming up…")
        : null
    );
  }
}
