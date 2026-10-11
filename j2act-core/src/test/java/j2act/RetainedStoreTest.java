package j2act;

import static j2act.RetainedStateTest.evict;
import static j2act.RetainedStateTest.remount;
import static j2act.RetainedStateTest.type;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import j2act.RetainedStateTest.CountingStorage;

/** ADR 0026: createRetainedStore keeps a store's per-session value, keyed by its static field. */
class RetainedStoreTest {

  static final class Filters {
    static final Store<String> branch = State.createRetainedStore("all");
    static final Store<List<String>> tags = State.createRetainedStore(Collections.emptyList());
    static final Store<String> plain = State.createStore("none");
  }

  static final class Branch extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("branch", T.div(
        T.span("branch=" + Filters.branch.get() + ";"),
        T.span("tags=" + Filters.tags.get() + ";"),
        T.span("plain=" + Filters.plain.get() + ";"),
        T.input().withId("branch").onChange(e -> Filters.branch.set(e.value())),
        T.input().withId("tag").onChange(e -> Filters.tags.set(Collections.singletonList(e.value()))),
        T.input().withId("plain").onChange(e -> Filters.plain.set(e.value()))));
    }
  }

  /** A page that never reads the stores. */
  static final class Home extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("home", T.div(T.span("home")));
    }
  }

  static Harness harness(CountingStorage storage) {
    return new Harness(path -> "/".equals(path) ? page("home", Home::new) : "/b".equals(path) ? page("branch", Branch::new) : null,
      b -> b.withReconnectGrace(Duration.ofMillis(100)).withRetainedStateStorage(storage));
  }

  private static PageMatch page(String id, java.util.function.Supplier<? extends LiveComponent> factory) {
    return PageMatch.of(Collections.singletonList(new PageMatch.Frame(id, factory)), Collections.emptyMap(), Collections.emptyList());
  }

  @Test
  void aRetainedStoreComesBackKeyedByItsStaticFieldAndAPlainStoreDoesNot() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(storage)) {
      h.load("/b", Exchange.empty());
      h.connect();
      type(h, "branch", "north");
      type(h, "tag", "urgent");
      type(h, "plain", "changed");
      String old = evict(h, storage);
      String snapshot = storage.inner.load(Retained.id(old)).get();
      assertTrue(snapshot.contains("store:j2act.RetainedStoreTest$Filters#branch"), snapshot);

      String html = h.load("/b", remount(old));
      assertTrue(html.contains("branch=north;"), html);
      assertTrue(html.contains("tags=[urgent];"), html);
      assertTrue(html.contains("plain=none;"), "a plain store is not retained: " + html);
    }
  }

  @Test
  void aRestoredStoreWaitsForItsFirstUseAcrossNavigationsAndSaves() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(storage)) {
      h.load("/b", Exchange.empty());
      h.connect();
      type(h, "branch", "north");
      String old = evict(h, storage);

      // Restored on a page that never reads it, then saved again untouched.
      h.load("/", remount(old));
      h.connect();
      String again = evict(h, storage);

      h.load("/", remount(again));
      h.connect();
      String patch = Harness.last(h.nav("/b"), "patch");
      assertTrue(patch.contains("branch=north;"), "a soft navigation still finds the store's value: " + patch);
    }
  }

  @Test
  void aRetainedStoreNoStaticFieldHoldsIsNotRetained() {
    CountingStorage storage = new CountingStorage();
    Store<String> local = State.createRetainedStore("start");
    try (Harness h = new Harness(() -> new LiveComponent() {
      @Override public Tag<?> render() {
        return T.page("l", T.div(T.span("local=" + local.get() + ";"), T.span("branch=" + Filters.branch.get() + ";"),
          T.input().withId("local").onChange(e -> local.set(e.value())),
          T.input().withId("branch").onChange(e -> Filters.branch.set(e.value()))));
      }
    }, b -> b.withReconnectGrace(Duration.ofMillis(100)).withRetainedStateStorage(storage))) {
      h.load();
      h.connect();
      type(h, "local", "changed");
      type(h, "branch", "south");
      String old = evict(h, storage);
      String html = h.load("/", remount(old));
      assertTrue(html.contains("local=start;"), html);
      assertTrue(html.contains("branch=south;"), html);
    }
  }
}
