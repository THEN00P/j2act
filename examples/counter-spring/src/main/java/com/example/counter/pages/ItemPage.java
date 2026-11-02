package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.router.Routes.notFound;
import static j2act.html.TagCreator.*;

import j2act.View;
import j2act.LiveComponent;
import j2act.Query;
import j2act.Signal;
import j2act.html.tags.HtmlTag;

/**
 * /items/{id}: the loader reads pathParam("id"), so navigating between items keeps this
 * instance (and its State) and refetches. Item 404 throws notFound() (ADR 0015).
 * loading() is a boundary (ADR 0007).
 */
public class ItemPage extends LiveComponent {
  private Query<String> item;

  @Override protected View setup() {
    var likes = createSignal(0);
    item = createMemo(() -> async(() -> {
    String id = pathParam("id");
    if ("404".equals(id)) {
      throw notFound();
    }
    Thread.sleep(150);
    return "Item " + id;
  }));
    return () -> {
    String name = item.get() == null ? "Loading…" : item.get();
    return html(
      head(
        title(name + " · j2act")
      ),
      body(
        h1(name),
        p("tab: " + (queryParam("tab") == null ? "overview" : queryParam("tab"))),
        button("likes " + likes.get())
          .withId("likes")
          .onClick(e -> likes.set(likes.get() + 1))
      )
    );
    };
  }

  /** Shown only if an item takes longer than the navigation hold (1s); fast loads never flash it. */
  @Override public HtmlTag loading() {
    return html(
      head(
        title("Loading item… · j2act")
      ),
      body(
        p("Loading item…")
          .withClass("muted")
      )
    );
  }
}
