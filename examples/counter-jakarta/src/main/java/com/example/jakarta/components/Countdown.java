package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import j2act.Client;
import j2act.ComponentTag;
import j2act.DomContent;
import j2act.Mount;
import j2act.State;
import j2act.Upload;
import j2act.html.tags.ButtonTag;
import j2act.html.tags.DivTag;

/**
 * A client module (ADR 0022): the countdown ticks in the browser (Countdown.client.js),
 * tells the server each tick, and shows a caption the server keeps rendering inside the
 * client's own DOM.
 */
public final class Countdown extends ComponentTag {

  /** A bean prop: the host's JSON binding (Jackson or JSON-B) writes it, the basic one would refuse. */
  public static final class Labels {
    public String getUnit() {
      return "s";
    }
  }

  /** Counts down in its div. */
  interface Timer extends Client {
    Mount<DivTag> mount(int seconds, Labels labels, Consumer<Integer> onTick, Runnable onDone, DomContent caption);

    /** Stops the countdown and answers with the seconds left. */
    CompletionStage<Integer> pause();

    void restart(int seconds);

    /** Places server content inside the countdown; a component stays live (ADR 0022). */
    void note(DomContent content);

    /** Sends the ticks so far as a text file through the Upload. */
    void export(Upload into);
  }

  private final Timer timer = client(Timer.class);
  private final State<Integer> ticks = state(5);
  private final State<String> status = state("running");
  private final Upload log = upload()
    .withAccept(".txt")
    .withMaxFileSize("1KB");

  /** Handed to note(): it keeps re-rendering on its own State inside the client's DOM. */
  static final class NoteBadge extends ComponentTag {
    private final State<Integer> clicks = state(0);

    @Override protected ButtonTag render() {
      return button("notes " + clicks.get())
        .withClass("note-badge")
        .onClick(e -> clicks.set(clicks.get() + 1));
    }
  }

  public static Countdown countdown() {
    return new Countdown();
  }

  @Override protected DivTag render() {
    return div(
      div()
        .withId("timer")
        .withClient(timer.mount(5, new Labels(), ticks::set, () -> status.set("done"), span("server saw " + ticks.get() + " left"))),
      button("Pause")
        .withId("timer-pause")
        .onClick(timer::pause, left -> status.set("paused at " + left)),
      button("Restart")
        .withId("timer-restart")
        .onClick(e -> {
          status.set("running");
          timer.restart(2);
        }),
      button("Note")
        .withId("timer-note")
        .onClick(e -> timer.note(new NoteBadge())),
      button("Hold")
        .withId("timer-hold")
        .onPointerDown(timer::pause, left -> status.set("held at " + left)),
      input()
        .withId("timer-key")
        .withPlaceholder("Enter pauses")
        .withKeyFilter("Enter")
        .onKeyDown(timer::pause, left -> status.set("paused by key at " + left)),
      button("Export")
        .withId("timer-export")
        .onClick(e -> timer.export(log)),
      span(status.get())
        .withId("timer-status"),
      span(log.isSuccess() ? "exported " + log.data().size() + " bytes" : "not exported")
        .withId("timer-export-status")
    );
  }
}
