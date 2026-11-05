package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;

import com.example.counter.services.TickBus;
import j2act.View;
import j2act.ComponentTag;
import j2act.html.tags.PTag;
import j2act.Signal;

/**
 * Push from outside the tab (ADR 0014): the scheduler thread calls update(),
 * which runs on this tab's lane. The effect's cleanup unsubscribes on unmount
 * and on tab eviction.
 */
public final class ClockFeed extends ComponentTag {

  @Inject private TickBus bus;

  public static ClockFeed clockFeed() {
    return new ClockFeed();
  }

  @Override protected View setup() {
    var last = createSignal("waiting for the first tick");
    var ticks = createSignal(0);
    onSettled(() -> bus.subscribe(time -> {
      last.set(time);
      ticks.update(n -> n + 1);
    }));
    return () -> p(
      text("Server time "),
      strong(last.get()),
      text(" · " + ticks.get() + " ticks pushed")
    );
  }
}
