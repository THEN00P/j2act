package com.example.spike.pages;

import static com.example.spike.components.SalesChart.salesChart;
import static j2act.Vite.vite;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Page;
import j2act.State;
import j2act.html.tags.HtmlTag;

/** Tailwind from Java class names, a TS client module, and a counter whose state must survive a re-import. */
public class HomePage extends LiveComponent implements Page {

  private final State<Integer> count = state(0);

  @Override public HtmlTag render() {
    return html(
      head(
        title("j2act spike · Gradle on WildFly"),
        vite("src/main/frontend/app.css")
      ),
      body(
        h1("Vite, Tailwind and TypeScript on WildFly")
          .withId("title")
          .withClass("text-3xl font-bold text-sky-700"),
        div(
          span("count " + count.get()).withId("count"),
          button("+1")
            .withId("inc")
            .withClass("ml-2 rounded bg-sky-600 px-3 py-1 text-white")
            .onClick(e -> count.set(count.get() + 1))
        ).withClass("my-4"),
        salesChart()
      ).withClass("p-6")
    );
  }
}
