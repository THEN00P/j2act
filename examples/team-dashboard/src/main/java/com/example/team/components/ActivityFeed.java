package com.example.team.components;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import com.example.team.events.ActivityBus;
import j2act.ComponentTag;
import j2act.State;
import j2act.html.tags.UlTag;

/**
 * Live feed pushed from outside the session. ActivityBus is the app's own
 * listener (a scheduler, a JMS consumer, Debezium); J2ACT ships no pub/sub.
 * The bus calls State.update() on its own thread, which is safe: the
 * read-modify-write runs on this session's lane, so no heartbeat is lost.
 * The unsubscribe cleanup runs on unmount, navigation away and eviction.
 */
public final class ActivityFeed extends ComponentTag {

  @Inject private ActivityBus bus;

  private final State<List<String>> entries = state(new ArrayList<>());

  public static ActivityFeed activityFeed() {
    return new ActivityFeed();
  }

  @Override protected UlTag render() {
    effect(() -> bus.subscribe(entry -> entries.update(current -> {
      List<String> next = new ArrayList<>(current);
      next.add(0, entry);
      return next.subList(0, Math.min(next.size(), 20));
    })));

    return ul(
      each(entries.get(), entry -> li(entry))
    );
  }
}
