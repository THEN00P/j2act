package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import j2act.View;
import j2act.ComponentTag;
import j2act.Memo;
import j2act.Download;
import j2act.Signal;
import j2act.html.tags.DivTag;

/**
 * A memo, an action and a download (ADR 0031, 0012): the write runs off the lane, and an optimistic
 * flag keeps the button pending for as long as it takes, past the ack, undoing itself when the
 * action ends. The download is pending until the browser has the last byte.
 */
public final class Publisher extends ComponentTag {

  public static Publisher publisher() {
    return new Publisher();
  }

  @Override protected View setup() {
    var paragraphs = createSignal(0);
    var published = createSignal("nothing yet");
    var length = createMemo(() -> paragraphs.get() >= 3 ? "long" : "short");
    var publishing = createOptimistic(false);
    var publish = action((Integer count) -> {
      publishing.set(true);
      Thread.sleep(600);
      published.set(count + " paragraphs");
    });
    var draft = createDownload((Integer count, OutputStream out) -> {
    for (int i = 1; i <= count; i++) {
      out.write(("Paragraph " + i + "\n").getBytes(StandardCharsets.UTF_8));
    }
  })
    .withFileName("draft.txt")
    .withContentType("text/plain;charset=UTF-8");
    return () -> div(
      button("Add paragraph").withId("draft-add").onClick(e -> paragraphs.set(paragraphs.get() + 1)),
      span(" draft: " + paragraphs.get() + " paragraphs, " + length.get() + " "),
      button(publishing.get() ? "Publishing…" : "Publish")
        .withId("publish")
        .withCondDisabled(publishing.get())
        .onClick(e -> publish.run(paragraphs.get())),
      button("Download draft").withId("draft-download").onClick(e -> draft.mutate(paragraphs.get())),
      p("download status: " + draft.status()).withId("download-status"),
      p("published: " + published.get()).withId("publish-status")
    );
  }
}
