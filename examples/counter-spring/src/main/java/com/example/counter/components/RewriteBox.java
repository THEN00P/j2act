package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.View;

/**
 * Fields the server edits while they are being typed in (ADR 0034): it rewrites a shorthand, rejects letters and clears
 * after a send, and what the user typed since is kept. The sleep stands in for a slow connection, so the server is
 * always behind the typing.
 */
public final class RewriteBox extends ComponentTag {

  public static RewriteBox rewriteBox() {
    return new RewriteBox();
  }

  @Override protected View setup() {
    var note = createSignal("");
    var digits = createSignal("");
    var line = createSignal("");
    var sent = createSignal("nothing");
    return () -> div(
      input()
        .withId("rewrite-note")
        .withAutocomplete("off")
        .withPlaceholder("Try :smile: and keep typing")
        .withValue(note.get())
        .onInput(e -> {
          Thread.sleep(120);
          // What was just undone stays undone.
          note.set(e.isUndo() ? e.value() : e.value().replace(":smile:", "😄"));
        }),
      input()
        .withId("rewrite-digits")
        .withAutocomplete("off")
        .withPlaceholder("Digits only")
        .withValue(digits.get())
        .onInput(e -> digits.set(e.value().replaceAll("[^0-9]", ""))),
      form(
        input()
          .withId("rewrite-line")
          .withName("line")
          .withAutocomplete("off")
          .withPlaceholder("Enter sends and clears")
          .withValue(line.get())
          .onInput(e -> line.set(e.value()))
      ).onSubmit(e -> {
        sent.set(line.get());
        line.set("");
      }),
      p("sent: " + sent.get()).withId("rewrite-sent")
    );
  }
}
