package com.example.counter.services;

import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * App-owned push source, not a j2act type (ADR 0014). A Spring scheduler here; in a
 * real app a JMS or Debezium listener. Listeners are called on the scheduler thread.
 */
@Component
public class TickBus {

  private final Set<Consumer<String>> listeners = ConcurrentHashMap.newKeySet();

  /** Returns the unsubscribe, so it can be an effect cleanup directly. */
  public Runnable subscribe(Consumer<String> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  public int listenerCount() {
    return listeners.size();
  }

  @Scheduled(fixedRate = 1000)
  void tick() {
    String now = LocalTime.now().withNano(0).toString();
    listeners.forEach(l -> l.accept(now));
  }
}
