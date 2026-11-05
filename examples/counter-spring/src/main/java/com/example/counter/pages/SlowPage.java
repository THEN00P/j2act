package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.View;

/**
 * A slow page with no loading(...) around its read, so the page before it stays up until this one is ready, and keeps
 * working meanwhile (ADR 0011). tools/browser-check.mjs clicks around while it loads.
 */
public class SlowPage extends LiveComponent {

  @Override protected View setup() {
    var answer = createMemo(() -> async(() -> {
      Thread.sleep(2500);
      return "Slow page ready";
    }));
    return () -> html(
      head(
        title("Slow · j2act")
      ),
      body(
        h1(answer.get())
      )
    );
  }
}
