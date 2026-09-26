package com.example.spike.components;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;

import j2act.Client;
import j2act.ComponentTag;
import j2act.Mount;
import j2act.State;
import j2act.html.tags.DivTag;

/** Chart.js from npm in a TypeScript client module, styled by a CSS module. */
public final class SalesChart extends ComponentTag {

  interface Bars extends Client {
    Mount<DivTag> mount(List<String> months, List<Integer> sales);
  }

  private static final List<String> MONTHS = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun");

  private final Bars bars = client(Bars.class);
  private final State<List<Integer>> sales = state(List.of(10, 20, 30));

  public static SalesChart salesChart() {
    return new SalesChart();
  }

  @Override protected DivTag render() {
    List<Integer> values = sales.get();
    return div(
      div()
        .withId("sales")
        .withClient(bars.mount(MONTHS.subList(0, values.size()), values)),
      button("Add a month")
        .withId("sales-add")
        .withClass("mt-2 rounded border px-3 py-1")
        .withCondDisabled(values.size() >= MONTHS.size())
        .onClick(e -> {
          List<Integer> next = new ArrayList<>(sales.get());
          next.add(5 * next.size());
          sales.set(next);
        })
    );
  }
}
