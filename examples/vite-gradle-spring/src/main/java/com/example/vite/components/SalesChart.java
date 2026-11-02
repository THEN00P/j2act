package com.example.vite.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;

import j2act.View;
import j2act.Client;
import j2act.ComponentTag;
import j2act.Mount;
import j2act.Signal;
import j2act.html.tags.DivTag;

/** Chart.js from npm in a TypeScript client module, styled by a CSS module. */
public final class SalesChart extends ComponentTag {
  private Signal<List<Integer>> sales;


  interface Bars extends Client {
    Mount<DivTag> mount(List<String> months, List<Integer> sales);
  }

  private static final List<String> MONTHS = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun");

  public static SalesChart salesChart() {
    return new SalesChart();
  }

  @Override protected View setup() {
    var bars = createClient(Bars.class);
    sales = createSignal(List.of(10, 20, 30));
    return () -> {
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
    };
  }
}
