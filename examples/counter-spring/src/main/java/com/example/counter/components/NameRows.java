package com.example.counter.components;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.Prop;
import j2act.State;

/** Stateful rows keyed on the row itself (ADR 0019): the open row stays open through a reorder. */
public final class NameRows extends ComponentTag {

  private final State<List<String>> names = state(Arrays.asList("Ada", "Grace", "Barbara", "Ken"));

  public static NameRows nameRows() {
    return new NameRows();
  }

  @Override protected ContainerTag render() {
    return div(
      button("Reverse")
        .onClick(e -> {
          List<String> reversed = new ArrayList<>(names.get());
          Collections.reverse(reversed);
          names.set(reversed);
        }),
      ul(
        each(names.get(), name -> Row.row()
          .withName(name)
          .withKey(name))
      )
    );
  }

  public static final class Row extends ComponentTag {

    private final Prop<String> name = prop();
    private final State<Boolean> open = state(false);

    public static Row row() {
      return new Row();
    }

    public Row withName(String n) {
      name.set(n);
      return this;
    }

    @Override protected ContainerTag render() {
      return li(
        button(open.get() ? "▾ " + name.get() : "▸ " + name.get())
          .onClick(e -> open.set(!open.get())),
        open.get()
          ? span(" expanded, State lives on the " + name.get() + " row")
          : null
      );
    }
  }
}
