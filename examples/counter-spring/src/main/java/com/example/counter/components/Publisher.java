package com.example.counter.components;

import static j2act.html.TagCreator.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import j2act.ComponentTag;
import j2act.Computed;
import j2act.Download;
import j2act.Mutation;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * computed(), mutation() and download() (ADR 0006, 0012): the write runs off the lane on
 * the variables passed to mutate(), and the button stays pending for as long as it takes,
 * past the ack. The download is pending until the browser has the last byte.
 */
public final class Publisher extends ComponentTag {

  private final State<Integer> paragraphs = state(0);
  private final State<String> published = state("nothing yet");
  private final Computed<String> length = computed(() -> paragraphs.get() >= 3 ? "long" : "short");
  private final Mutation<Integer, String> publish = mutation((Integer count) -> {
    Thread.sleep(600);
    return count + " paragraphs";
  })
    .withKey("publish")
    .onSuccess(published::set);
  private final Download<Integer> draft = download((Integer count, OutputStream out) -> {
    for (int i = 1; i <= count; i++) {
      out.write(("Paragraph " + i + "\n").getBytes(StandardCharsets.UTF_8));
    }
  })
    .withFileName("draft.txt")
    .withContentType("text/plain;charset=UTF-8");

  public static Publisher publisher() {
    return new Publisher();
  }

  @Override protected DivTag render() {
    return div(
      button("Add paragraph").withId("draft-add").onClick(e -> paragraphs.set(paragraphs.get() + 1)),
      span(" draft: " + paragraphs.get() + " paragraphs, " + length.get() + " "),
      button(publish.isPending() ? "Publishing…" : "Publish")
        .withId("publish")
        .withCondDisabled(publish.isPending())
        .onClick(e -> publish.mutate(paragraphs.get())),
      button("Download draft").withId("draft-download").onClick(e -> draft.mutate(paragraphs.get())),
      p("download status: " + draft.status()).withId("download-status"),
      p("publish status: " + publish.status() + " · published: " + published.get()).withId("publish-status")
    );
  }
}
