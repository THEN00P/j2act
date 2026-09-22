package com.example.team.events;

import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * App-owned push source, not a J2ACT type. Here a Spring scheduler; in
 * production it could be a Debezium or JMS listener. Deciding who may see
 * which entry is this class's job, as with any pub/sub.
 */
@Component
public class ActivityBus {

  private final Set<Consumer<String>> listeners = new CopyOnWriteArraySet<>();

  /** Returns the unsubscribe, so it can be an effect cleanup directly. */
  public Runnable subscribe(Consumer<String> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  @Scheduled(fixedRate = 5000)
  void heartbeat() {
    String entry = "heartbeat at " + LocalTime.now().withNano(0);
    listeners.forEach(l -> l.accept(entry));
  }
}
