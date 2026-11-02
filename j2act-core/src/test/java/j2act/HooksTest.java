package j2act;

import static j2act.Reactive.createEffect;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.onCleanup;
import static j2act.Reactive.onSettled;
import static j2act.Reactive.useTab;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** ADR 0031: effects start with the socket, two halves, cleanup order, onSettled, connected(). */
class HooksTest {

  static final List<String> LOG = Collections.synchronizedList(new ArrayList<>());

  static final class Watching extends LiveComponent {
    @Override protected View setup() {
      var count = createSignal(0);
      var other = createSignal(0);
      createEffect(count::get, (n, before) -> {
        int seenOther = other.get();
        LOG.add("run " + n + " was " + before + " other " + seenOther);
        return () -> LOG.add("clean " + n);
      });
      return () -> T.page("t", T.div(
        T.button("up").onClick(e -> count.set(count.get() + 1)),
        T.button("other " + other.get()).onClick(e -> other.set(other.get() + 1)),
        T.button("same").onClick(e -> count.set(count.get()))));
    }
  }

  @Test
  void anEffectStartsWhenTheSocketConnectsAndOnlyItsWatchedHalfIsTracked() {
    LOG.clear();
    try (Harness h = new Harness(Watching::new)) {
      h.load();
      assertTrue(LOG.isEmpty(), "the HTML render runs no effect: " + LOG);
      h.connect();
      Harness.eventually(() -> LOG.contains("run 0 was null other 0"), "the first run, on connect");

      h.click(Harness.clickOn(h.html, "up"));
      Harness.eventually(() -> LOG.contains("run 1 was 0 other 0"), "it acts when the watched value changed");
      assertEquals("clean 0", LOG.get(1), "its cleanup ran first: " + LOG);

      int before = LOG.size();
      h.click(Harness.clickOn(h.html, "other 0"));
      assertEquals(before, LOG.size(), "a read in the second half subscribes nothing: " + LOG);
    }
  }

  static final class Child extends ComponentTag {
    @Override protected View setup() {
      onCleanup(() -> LOG.add("child registration"));
      createEffect(() -> 1, n -> () -> LOG.add("child effect"));
      return () -> T.span("child");
    }
  }

  static final class Parent extends ComponentTag {
    @Override protected View setup() {
      onCleanup(() -> LOG.add("parent registration"));
      return () -> T.div(new Child());
    }
  }

  static final class Unmounts extends LiveComponent {
    @Override protected View setup() {
      var shown = createSignal(true);
      return () -> T.page("t", T.div(
        shown.get() ? new Parent() : null,
        T.button("hide").onClick(e -> shown.set(false))));
    }
  }

  @Test
  void childrenCleanUpBeforeTheirParentLastCreatedFirst() {
    LOG.clear();
    try (Harness h = new Harness(Unmounts::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "hide"));
      Harness.eventually(() -> LOG.size() == 3, "three cleanups: " + LOG);
      assertEquals(List.of("child effect", "child registration", "parent registration"), LOG);
    }
  }

  static final class Settles extends LiveComponent {
    @Override protected View setup() {
      var ticks = createSignal(0);
      onSettled(() -> {
        LOG.add("settled");
        return () -> LOG.add("settled cleanup");
      });
      return () -> T.page("t", T.button("tick " + ticks.get()).onClick(e -> ticks.set(ticks.get() + 1)));
    }
  }

  @Test
  void onSettledRunsOnceAfterConnecting() {
    LOG.clear();
    try (Harness h = new Harness(Settles::new)) {
      h.load();
      assertTrue(LOG.isEmpty(), "not before the socket: " + LOG);
      h.connect();
      Harness.eventually(() -> LOG.contains("settled"), "settled once connected");
      h.click(Harness.clickOn(h.html, "tick 0"));
      assertEquals(List.of("settled"), LOG, "once: " + LOG);
    }
  }

  static final class SettleWrites extends LiveComponent {
    @Override protected View setup() {
      var opened = createSignal("not yet");
      onSettled(() -> {
        opened.set("opened");
        return null;
      });
      return () -> T.page("t", T.span(opened.get()));
    }
  }

  @Test
  void whatOnSettledWritesGoesOutWithoutAnotherEvent() {
    try (Harness h = new Harness(SettleWrites::new)) {
      h.load();
      h.connect();
      h.awaitPatch(0, p -> p.contains(">opened<"));
    }
  }

  static final class WatchesSocket extends LiveComponent {
    @Override protected View setup() {
      createEffect(() -> useTab().connected(), live -> {
        LOG.add("live " + live);
        return live ? () -> LOG.add("stopped") : null;
      });
      return () -> T.page("t", T.div());
    }
  }

  @Test
  void anEffectCanWatchTheSocket() {
    LOG.clear();
    try (Harness h = new Harness(WatchesSocket::new)) {
      h.load();
      h.connect();
      Harness.eventually(() -> LOG.contains("live true"), "running while connected");
      h.disconnect();
      Harness.eventually(() -> LOG.contains("live false"), "told about the drop: " + LOG);
      assertEquals(List.of("live true", "stopped", "live false"), LOG);
    }
  }
}
