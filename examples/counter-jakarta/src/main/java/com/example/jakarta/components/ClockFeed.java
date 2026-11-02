package com.example.jakarta.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;

import com.example.jakarta.model.TickBus;
import j2act.View;
import j2act.ComponentTag;
import j2act.Signal;
import j2act.html.tags.PTag;

/** Pushes from a managed scheduler thread; update() runs on the tab's lane (ADR 0014). */
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
