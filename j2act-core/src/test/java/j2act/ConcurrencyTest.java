package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** ADR 0001 and 0014: one lane per session, foreign writes queued onto it, context never leaks. */
class ConcurrencyTest {

  /** Stands in for the app's scheduler, JMS or Debezium listener. */
  static final class Bus {
    final Set<Consumer<String>> listeners = ConcurrentHashMap.newKeySet();

    Runnable subscribe(Consumer<String> listener) {
      listeners.add(listener);
      return () -> listeners.remove(listener);
    }

    void emit(String entry) {
      listeners.forEach(l -> l.accept(entry));
    }
  }

  static final class Feed extends LiveComponent {
    private final Bus bus;
    private final State<List<String>> entries = state(Collections.emptyList());

    Feed(Bus bus) {
      this.bus = bus;
    }

    @Override public Tag<?> render() {
      effect(() -> bus.subscribe(entry -> entries.update(current -> {
        List<String> next = new ArrayList<>(current);
        next.add(entry);
        return next;
      })));
      return T.page("feed", T.span("count=" + entries.get().size()));
    }
  }

  @Test
  void foreignThreadUpdatesAreSerializedWithoutLostWrites() throws Exception {
    Bus bus = new Bus();
    try (Harness h = new Harness(() -> new Feed(bus))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      ExecutorService producers = Executors.newFixedThreadPool(8);
      CountDownLatch start = new CountDownLatch(1);
      for (int t = 0; t < 8; t++) {
        int thread = t;
        producers.execute(() -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            return;
          }
          for (int i = 0; i < 250; i++) {
            bus.emit(thread + ":" + i);
          }
        });
      }
      start.countDown();
      producers.shutdown();
      assertTrue(producers.awaitTermination(10, TimeUnit.SECONDS));
      h.awaitPatch(from, html -> html.contains("count=2000"));
    }
  }

  @Test
  void effectCleanupUnsubscribesWhenTheSessionIsDiscarded() {
    Bus bus = new Bus();
    try (Harness h = new Harness(() -> new Feed(bus))) {
      h.load();
      h.connect();
      assertEquals(1, bus.listeners.size());
      h.engine.onMessage(h.conn, Json.object("t", "bye"));
      Harness.eventually(() -> bus.listeners.isEmpty(), "effect cleanup");
      assertEquals(0, h.engine.sessionCount());
      bus.emit("after dispose");
    }
  }

  static final class Holder extends LiveComponent {
    static final AtomicReference<State<Integer>> handle = new AtomicReference<>();
    private final State<Integer> n = state(0);

    @Override public Tag<?> render() {
      handle.set(n);
      return T.page("t", T.span("n=" + n.get()));
    }
  }

  @Test
  void laneContextIsClearedBeforeThePoolThreadIsReused() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    try (J2Act engine = J2Act.builder(p -> new PageMatch(Holder::new, Collections.emptyMap()))
      .withExecutor(single)
      .build()) {
      assertEquals(200, engine.serve("/").status());
      AtomicReference<Object> leaked = new AtomicReference<>("unset");
      single.submit(() -> leaked.set(Session.current() != null ? Session.current() : Tracking.observer())).get();
      assertNull(leaked.get());
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  void writesToADisposedSessionAreInert() {
    try (Harness h = new Harness(Holder::new)) {
      h.load();
      h.connect();
      h.engine.onMessage(h.conn, Json.object("t", "bye"));
      Harness.eventually(() -> h.engine.sessionCount() == 0, "session discarded");
      Holder.handle.get().set(99);
      Holder.handle.get().update(x -> x + 1);
    }
  }
}
