package com.example.team.components;

import static j2act.html.TagCreator.*;
import static j2act.ui.Ui.*;

import j2act.ClickEvent;
import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.State;
import j2act.Variant;

/**
 * Stateful counter. Factory takes children only; label arrives via with*.
 * State binds to the tree slot (ADR 0019): two siblings never share state
 * or morph targets.
 */
public final class Counter {

  private Counter() {}

  public static CounterTag counter() {
    return new CounterTag();
  }

  public static final class CounterTag extends ComponentTag {
    private String label = "Clicked";

    public CounterTag withLabel(String label) {
      this.label = label;
      return this;
    }

    @Override protected ContainerTag render() {
      State<Integer> count = state(0);
      return div(
        UI.button(label + " " + count.get() + " times")
          .withVariant(Variant.PRIMARY)
          .onClick((ClickEvent e) -> count.set(count.get() + 1)),
        count.get() > 5 
          ? p("warming up…") 
          : null
      );
    }
  }
}
