package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.router.Routes.notFound;
import static j2act.html.TagCreator.*;

import j2act.View;
import j2act.LiveComponent;
import j2act.Signal;
import j2act.html.tags.HtmlTag;

/**
 * /items/{id}: the load reads pathParam("id"), so navigating between items keeps this instance
 * and its state, and holds the new id until the item arrived (ADR 0031). It is read outside every
 * loading(...), so the page waits for it and item 404 throws a real notFound() (ADR 0015).
 */
public class ItemPage extends LiveComponent {

  @Override protected View setup() {
    var likes = createSignal(0);
    var item = createMemo(() -> {
      String id = pathParam("id");
      return async(() -> {
        if ("404".equals(id)) {
          throw notFound();
        }
        Thread.sleep(150);
        return "Item " + id;
      });
    });
    return () -> html(
      head(
        title(item.get() + " · j2act")
      ),
      body(
        h1(item.get()),
        p("tab: " + (queryParam("tab") == null ? "overview" : queryParam("tab"))),
        button("likes " + likes.get())
          .withId("likes")
          .onClick(e -> likes.set(likes.get() + 1))
      )
    );
  }
}
