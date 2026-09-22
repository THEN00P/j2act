package com.example.counter.components;

import static j2act.html.TagCreator.*;

import javax.inject.Inject;

import com.example.counter.services.TickBus;
import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.State;

/**
 * Push from outside the session (ADR 0014): the scheduler thread calls update(),
 * which runs on this session's lane. The effect's cleanup unsubscribes on unmount
 * and on session eviction.
 */
public final class ClockFeed extends ComponentTag {

  @Inject private TickBus bus;

  private final State<String> last = state("waiting for the first tick");
  private final State<Integer> ticks = state(0);

  public static ClockFeed clockFeed() {
    return new ClockFeed();
  }

  @Override protected ContainerTag render() {
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
