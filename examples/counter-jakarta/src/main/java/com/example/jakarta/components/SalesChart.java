package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;

import j2act.Client;
import j2act.ComponentTag;
import j2act.DomContent;
import j2act.Mount;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * The Chart.js spike (ADR 0022): Chart.js draws in the browser, imported by bare name
 * through the mvnpm import map, while the legend is server-rendered and lives inside the
 * chart's own box as a slot. New data reaches the chart through update(), not a remount.
 */
public final class SalesChart extends ComponentTag {

  /** A bar chart of monthly sales. */
  interface Bars extends Client {
    Mount<DivTag> mount(List<String> months, List<Integer> sales, DomContent legend);
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
        .withClient(bars.mount(MONTHS.subList(0, values.size()), values, legend(values))),
      button("Add a month")
        .withId("sales-add")
        .withCondDisabled(values.size() >= MONTHS.size())
        .onClick(e -> {
          List<Integer> next = new ArrayList<>(sales.get());
          next.add(5 * next.size());
          sales.set(next);
        })
    );
  }

  /** Rendered by the server, shown inside the chart's box, with a handler of its own. */
  private DivTag legend(List<Integer> values) {
    int total = values.stream().mapToInt(Integer::intValue).sum();
    return div(
      span("total " + total)
        .withClass("sales-total"),
      button("Bump " + MONTHS.get(values.size() - 1))
        .withClass("sales-bump")
        .onClick(e -> {
          List<Integer> next = new ArrayList<>(sales.get());
          next.set(next.size() - 1, next.get(next.size() - 1) + 10);
          sales.set(next);
        })
    ).withClass("sales-legend");
  }
}
