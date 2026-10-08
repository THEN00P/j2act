package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/** ADR 0026: snapshots on eviction, restore on remount, keys by slot and field name. */
class RetainedStateTest {

  static final class Form extends LiveComponent {
    private final State<String> name = retainedState("");
    private final State<Integer> age = retainedState(0);
    private final State<String> scratch = state("");

    @Override public Tag<?> render() {
      return T.page("form", T.div(
        T.span("name=" + name.get() + ";"),
        T.span("age=" + age.get() + ";"),
        T.span("scratch=" + scratch.get() + ";"),
        T.input().withId("name").onChange(e -> name.set(e.value())),
        T.input().withId("age").onChange(e -> age.set(Integer.parseInt(e.value()))),
        T.input().withId("scratch").onChange(e -> scratch.set(e.value()))));
    }
  }

  /** Counts what reaches storage, around the in-memory default. */
  static class CountingStorage implements RetainedStateStorage {
    final MemoryRetainedStateStorage inner = new MemoryRetainedStateStorage(1000);
    final AtomicInteger saves = new AtomicInteger();
    final AtomicInteger loads = new AtomicInteger();

    @Override public Optional<String> load(String id) {
      loads.incrementAndGet();
      return inner.load(id);
    }

    @Override public void save(String id, String snapshot, Instant expiresAt) {
      saves.incrementAndGet();
      inner.save(id, snapshot, expiresAt);
    }

    @Override public void delete(String id) {
      inner.delete(id);
    }

    boolean has(String token) {
      return inner.load(Retained.id(token)).isPresent();
    }
  }

  static Harness harness(Supplier<? extends LiveComponent> page, CountingStorage storage, Consumer<J2Act.Builder> config) {
    return new Harness(page, b -> {
      b.withReconnectGrace(Duration.ofMillis(100)).withRetainedStateStorage(storage);
      config.accept(b);
    });
  }

  static Harness harness(Supplier<? extends LiveComponent> page, CountingStorage storage) {
    return harness(page, storage, b -> { });
  }

  /** The socket drops and the grace window ends: the session is evicted and its snapshot saved. */
  static String evict(Harness h, CountingStorage storage) {
    String token = h.token;
    h.engine.onClose(h.conn);
    Harness.eventually(() -> h.engine.sessionCount() == 0 && storage.has(token), "eviction with a snapshot");
    return token;
  }

  /** The remount runtime.js makes once its session is gone, naming the old page's token. */
  static Exchange remount(String oldToken, String... cookies) {
    Map<String, String> jar = new HashMap<>();
    for (int i = 0; i < cookies.length; i += 2) {
      jar.put(cookies[i], cookies[i + 1]);
    }
    return Exchange.of(jar, Collections.singletonMap(Retained.RESTORE_HEADER, Collections.singletonList(oldToken)));
  }

  static void type(Harness h, String id, String value) {
    h.fire(Harness.handlerOn(h.html, "change", id), value);
  }

  @Test
  void retainedFieldsComeBackAfterTheGraceWindowAndPlainStateDoesNot() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      type(h, "age", "36");
      type(h, "scratch", "draft");
      String old = evict(h, storage);

      String html = h.load("/", remount(old));
      assertTrue(html.contains("name=Ada;"), html);
      assertTrue(html.contains("age=36;"), html);
      assertTrue(html.contains("scratch=;"), "plain State is not retained: " + html);
      assertNotEquals(old, h.token, "the restored page gets a new token");
      assertFalse(storage.has(old), "a snapshot is used once");

      h.connect();
      type(h, "name", "Grace");
      assertTrue(h.conn.since(0, m -> "patch".equals(m.get("t"))).stream().anyMatch(m -> m.get("h").contains("name=Grace;")),
        "the restored session is live");
    }
  }

  @Test
  void aReloadStartsEmpty() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      evict(h, storage);
      String html = h.load();
      assertTrue(html.contains("name=;"), html);
    }
  }

  @Test
  void aSnapshotRestoresOnlyOnce() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = evict(h, storage);
      assertTrue(h.load("/", remount(old)).contains("name=Ada;"));
      assertTrue(h.load("/", remount(old)).contains("name=;"), "a second remount with the old token gets nothing");
    }
  }

  @Test
  void anotherIdentityGetsNothingAndAnonymousOnlyMatchesAnonymous() {
    Consumer<J2Act.Builder> identity = b -> b.withIdentity(ex -> ex.cookie("user")
      .map(u -> AuthCtx.of(u, Collections.emptySet())).orElse(AuthCtx.anonymous()));
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage, identity)) {
      h.load("/", Exchange.of(Collections.singletonMap("user", "alice"), Collections.emptyMap()));
      h.connect();
      type(h, "name", "Ada");
      String old = evict(h, storage);
      assertTrue(h.load("/", remount(old, "user", "bob")).contains("name=;"), "bob does not get alice's draft");
      assertFalse(storage.has(old), "a failed check still uses up the snapshot");

      h.load();
      h.connect();
      type(h, "name", "anon");
      old = evict(h, storage);
      assertTrue(h.load("/", remount(old, "user", "alice")).contains("name=;"), "anonymous is not carried into a login");

      h.load("/", Exchange.of(Collections.singletonMap("user", "alice"), Collections.emptyMap()));
      h.connect();
      type(h, "name", "Ada");
      old = evict(h, storage);
      assertTrue(h.load("/", remount(old, "user", "alice")).contains("name=Ada;"), "the same user gets it back");
    }
  }

  @Test
  void entriesMatchByFieldNameAndAValueThatNoLongerReadsStartsFromItsInitialValue() {
    CountingStorage storage = new CountingStorage();
    Map<String, String> entries = new HashMap<>();
    // As an older deployment might have saved them: another order, a removed field, a changed type.
    entries.put("frame:page#age", "\"thirty\"");
    entries.put("frame:page#removed", "1");
    entries.put("frame:page#name", "\"Ada\"");
    storage.save(Retained.id("old-token"), Retained.encode(System.currentTimeMillis() + 60_000, null, entries),
      Instant.now().plusSeconds(60));
    try (Harness h = harness(Form::new, storage)) {
      String html = h.load("/", remount("old-token"));
      assertTrue(html.contains("name=Ada;"), html);
      assertTrue(html.contains("age=0;"), "an entry that no longer reads drops only itself: " + html);
    }
  }

  @Test
  void anExpiredSnapshotIsNotRestored() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage, b -> b.withRetainedStateRetention(Duration.ofMillis(1)))) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = evict(h, storage);
      sleep(20);
      assertTrue(h.load("/", remount(old)).contains("name=;"));
    }
  }

  @Test
  void anIdleSessionIsSavedBeforeItsClientHearsExpired() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage, b -> b.withIdleTimeout(Duration.ofMillis(300)))) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = h.token;
      int from = h.conn.size();
      h.conn.await(from, m -> "expired".equals(m.get("t")));
      assertTrue(storage.has(old), "the remount that expired triggers must find the snapshot");
      assertTrue(h.load("/", remount(old)).contains("name=Ada;"));
    }
  }

  static final class Plain extends LiveComponent {
    private final State<String> text = state("");

    @Override public Tag<?> render() {
      return T.page("plain", T.input().withId("text").onChange(e -> text.set(e.value())));
    }
  }

  @Test
  void sessionsWithoutRetainedValuesNeverTouchStorage() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Plain::new, storage)) {
      h.load();
      h.connect();
      type(h, "text", "x");
      h.engine.onClose(h.conn);
      Harness.eventually(() -> h.engine.sessionCount() == 0, "eviction");
      sleep(100);
      assertEquals(0, storage.saves.get());
      assertEquals(0, storage.loads.get(), "a page load without the restore header reads nothing");
    }
  }

  @Test
  void nullValuesAreNotSaved() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(NullableForm::new, storage)) {
      h.load();
      h.connect();
      h.engine.onClose(h.conn);
      Harness.eventually(() -> h.engine.sessionCount() == 0, "eviction");
      sleep(100);
      assertEquals(0, storage.saves.get(), "a session whose only retained value is null saves nothing");
    }
  }

  static final class NullableForm extends LiveComponent {
    private final State<String> note = retainedState(null);

    @Override public Tag<?> render() {
      return T.page("n", T.span("note=" + note.get()));
    }
  }

  static List<String> rowIds = Arrays.asList("a", "b", "c");

  static final class Row extends ComponentTag {
    private final State<String> draft = retainedState("");
    private String id;

    Row withId(String id) {
      this.id = id;
      return this;
    }

    @Override protected Tag<?> render() {
      return T.div(T.span(id + "=" + draft.get() + ";"), T.input().withId("row-" + id).onChange(e -> draft.set(e.value())));
    }
  }

  static final class Rows extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("rows", T.div(T.each(rowIds, id -> new Row().withId(id).withKey(id))));
    }
  }

  @Test
  void keyedRowsGetTheirOwnDraftBackAfterTheListReorders() {
    rowIds = Arrays.asList("a", "b", "c");
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Rows::new, storage)) {
      h.load();
      h.connect();
      type(h, "row-b", "bee");
      String old = evict(h, storage);
      rowIds = Arrays.asList("c", "x", "b", "a");
      String html = h.load("/", remount(old));
      assertTrue(html.contains("b=bee;"), html);
      assertTrue(html.contains("a=;") && html.contains("c=;") && html.contains("x=;"), html);
    } finally {
      rowIds = Arrays.asList("a", "b", "c");
    }
  }

  static final class Money {
    final long cents;

    Money(long cents) {
      this.cents = cents;
    }
  }

  static final class Price extends LiveComponent {
    private final State<Money> price = retainedState(new Money(0));

    @Override public Tag<?> render() {
      return T.page("p", T.div(
        T.span("price=" + price.get().cents + ";"),
        T.input().withId("price").onChange(e -> price.set(new Money(Long.parseLong(e.value()))))));
    }
  }

  @Test
  void aCodecWritesTheTypeTheJsonBindingCannot() {
    CountingStorage storage = new CountingStorage();
    RetainedCodec<Money> codec = new RetainedCodec<Money>() {
      @Override public String write(Money value) {
        return String.valueOf(value.cents);
      }

      @Override public Money read(String text) {
        return new Money(Long.parseLong(text));
      }
    };
    try (Harness h = harness(Price::new, storage, b -> b.withRetainedCodec(Money.class, codec))) {
      h.load();
      h.connect();
      type(h, "price", "1250");
      String old = evict(h, storage);
      assertTrue(h.load("/", remount(old)).contains("price=1250;"));
    }
  }

  static final class LocalRetained extends LiveComponent {
    @Override public Tag<?> render() {
      State<String> text = retainedState("");
      return T.page("l", T.div(T.span("text=" + text.get() + ";"), T.input().withId("text").onChange(e -> text.set(e.value()))));
    }
  }

  @Test
  void aRetainedLocalIsKeyedByCreationOrder() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(LocalRetained::new, storage)) {
      h.load();
      h.connect();
      type(h, "text", "kept");
      String old = evict(h, storage);
      assertTrue(storage.inner.load(Retained.id(old)).get().contains("frame:page#@0"));
      assertTrue(h.load("/", remount(old)).contains("text=kept;"));
    }
  }

  @Test
  void theInMemoryDefaultDropsTheOldestPastItsCap() {
    MemoryRetainedStateStorage storage = new MemoryRetainedStateStorage(2);
    Instant later = Instant.now().plusSeconds(60);
    storage.save("1", "{}", later);
    storage.save("2", "{}", later);
    storage.save("3", "{}", later);
    assertFalse(storage.load("1").isPresent());
    assertTrue(storage.load("3").isPresent());
    storage.sweep(later);
    assertEquals(0, storage.size());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
