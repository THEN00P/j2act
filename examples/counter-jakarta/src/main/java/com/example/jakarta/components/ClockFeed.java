package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;

import com.example.jakarta.model.TickBus;
import j2act.ComponentTag;
import j2act.State;
import j2act.html.tags.PTag;

/** Pushes from a managed scheduler thread; update() runs on the session's lane (ADR 0014). */
public final class ClockFeed extends ComponentTag {

  @Inject private TickBus bus;

  private final State<String> last = state("waiting for the first tick");
  private final State<Integer> ticks = state(0);

  public static ClockFeed clockFeed() {
    return new ClockFeed();
  }

  @Override protected PTag render() {
    effect(() -> bus.subscribe(time -> {
      last.set(time);
      ticks.update(n -> n + 1);
    }));
    return p(
      text("Server time "),
      strong(last.get()),
      text(" · " + ticks.get() + " ticks pushed")
    );
  }
}
