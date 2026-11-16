package j2act.internal.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValueEqualityTest {
  static class Id {
    final int id;
    Id(int id) { this.id = id; }
    @Override public boolean equals(Object o) { return o instanceof Id && ((Id) o).id == id; }
    @Override public int hashCode() { return id; }
  }

  static final class Entity extends Id {
    final String status;
    Entity(String status) { super(1); this.status = status; }
  }

  static final class Cycle extends Id {
    final Cycle next = this;
    Cycle() { super(1); }
  }

  // Its inherited JDK state is inaccessible without opening java.base.
  static final class OpaqueDate extends java.util.Date {
    OpaqueDate() { super(0); }
  }

  // A bean with the equality Lombok @Data generates, including a nested application value.
  static final class Dto {
    private final Object value;
    Dto(Object value) { this.value = value; }
    @Override public boolean equals(Object o) {
      return o instanceof Dto && java.util.Objects.equals(value, ((Dto) o).value);
    }
    @Override public int hashCode() { return value.hashCode(); }
  }

  @Test void checksStateBeyondEqualsIncludingInheritedAndNestedState() {
    var old = new Entity("old");
    var fresh = new Entity("fresh");
    assertEquals(old, fresh);
    assertFalse(ValueEquality.same(old, fresh));
    assertFalse(ValueEquality.same(List.of(old), List.of(fresh)));
    assertFalse(ValueEquality.same(Set.of(old), Set.of(fresh)));
    assertFalse(ValueEquality.same(Map.of("row", old), Map.of("row", fresh)));
    assertFalse(ValueEquality.same(Map.of(old, "row"), Map.of(fresh, "row")));
    assertFalse(ValueEquality.same(Optional.of(old), Optional.of(fresh)));
    assertFalse(ValueEquality.same(new Dto(List.of(old)), new Dto(List.of(fresh))));
    assertTrue(ValueEquality.same(old, old));
    assertTrue(ValueEquality.same(old, new Entity("old")));
  }

  @Test void keepsJdkValueEqualityAndConservativelyRejectsDeepCopies() {
    assertTrue(ValueEquality.same(new Dto(List.of("a")), new Dto(List.of("a"))));
    assertTrue(ValueEquality.same(Set.of("a", "b"), Set.of("b", "a")));
    assertFalse(ValueEquality.same(0.0, -0.0));
    assertTrue(ValueEquality.same(Double.NaN, Double.NaN));
    assertTrue(ValueEquality.same(new java.util.Date(0), new java.util.Date(0)));
    assertFalse(ValueEquality.same(new Object(), new Object()));
    assertFalse(ValueEquality.same(null, "a"));
    assertTrue(ValueEquality.same(null, null));
    Object a = "x";
    Object b = "x";
    for (int i = 0; i < 40; i++) {
      a = new Dto(a);
      b = new Dto(b);
    }
    assertFalse(ValueEquality.same(a, b));
    assertFalse(ValueEquality.same(new Cycle(), new Cycle()));
    assertFalse(ValueEquality.same(new OpaqueDate(), new OpaqueDate()));
    assertFalse(ValueEquality.same(new OpaqueDate(), new OpaqueDate()));
  }

  @Test void equalNewDtoAndFilteredListStillCutMemoReaders() {
    Runtime.Local local = new Runtime.Local();
    Runtime rt = local.runtime;
    var source = rt.createSignal(0);
    AtomicInteger computes = new AtomicInteger();
    AtomicInteger renders = new AtomicInteger();
    Owner root = rt.createRoot(o -> {
      var memo = rt.createMemo(prev -> {
        source.get();
        computes.incrementAndGet();
        return new Dto(List.of("a", "b").stream().filter(s -> s.equals("a"))
          .collect(java.util.stream.Collectors.toList()));
      });
      rt.createRenderEffect(prev -> memo.get(), (value, prev) -> {
        renders.incrementAndGet();
        return null;
      });
      return o;
    });
    rt.flush();
    source.set(1);
    rt.flush();
    assertEquals(2, computes.get());
    assertEquals(1, renders.get());
    root.dispose();
  }

  @Test void equalIdWriteAndItsAsyncDerivationRevealTogether() {
    Runtime.Local local = new Runtime.Local();
    Runtime rt = local.runtime;
    var source = rt.createSignal(new Entity("old"));
    var pending = new java.util.concurrent.CompletableFuture<String>();
    var screen = new java.util.ArrayList<String>();
    Owner root = rt.createRoot(o -> {
      var memo = rt.<String>createMemo(prev -> source.get().status.equals("old")
        ? java.util.concurrent.CompletableFuture.completedFuture("OLD") : pending);
      rt.<String>createRenderEffect(prev -> source.get().status + ":" + memo.get(), (value, prev) -> {
        screen.add(value);
        return null;
      });
      return o;
    });
    rt.flush();
    source.set(new Entity("fresh"));
    rt.flush();
    local.run();
    assertEquals(List.of("old:OLD"), screen);
    assertEquals("old", rt.untrack(source::get).status);
    pending.complete("FRESH");
    local.run();
    rt.flush();
    assertEquals(List.of("old:OLD", "fresh:FRESH"), screen);
    root.dispose();
  }

  @Test void customComparatorStillControlsThePrimitive() {
    Runtime rt = new Runtime.Local().runtime;
    var old = new Entity("old");
    var signal = rt.createSignal(old, new Runtime.Options<Entity>().equals(Object::equals));
    signal.set(new Entity("fresh"));
    rt.flush();
    assertSame(old, signal.get());
  }
}
