package com.example.jakarta.model;

import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;

/**
 * App-owned push source (ADR 0014), here the container's scheduled executor. In a real
 * app it could be a JMS or Debezium listener. Listeners are called on a managed thread.
 */
@ApplicationScoped
public class TickBus {

  @Resource
  private ManagedScheduledExecutorService scheduler;

  private final Set<Consumer<String>> listeners = ConcurrentHashMap.newKeySet();

  void start(@Observes Startup startup) {
    scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
  }

  /** Returns the unsubscribe, so it can be an effect cleanup directly. */
  public Runnable subscribe(Consumer<String> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  public int listenerCount() {
    return listeners.size();
  }

  private void tick() {
    String now = LocalTime.now().withNano(0).toString();
    listeners.forEach(l -> l.accept(now));
  }
}
