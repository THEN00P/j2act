package j2act.internal.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.signals.Runtime.Accessor;
import j2act.internal.signals.Runtime.SignalRef;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Solid's own tests for the base engine, ported from packages/signals/tests at 9449845: createSignal.test.ts,
 * flush.test.ts, onCleanup.test.ts, graph.test.ts and createEffect.test.ts. Names are Solid's. What is not
 * ported is listed in docs/spikes/solid-port-tests.md.
 */
class SolidBaseTest {

  private final Runtime.Local local = new Runtime.Local();
  private final Runtime rt = local.runtime;

  @AfterEach void flushAfter() {
    rt.flush();
  }

  /** vi.fn(). */
  private static final class Fn {
    final AtomicInteger calls = new AtomicInteger();
    final List<Object> args = new ArrayList<>();

    Runnable effect(Object value, Object prev) {
      calls.incrementAndGet();
      args.add(value);
      args.add(prev);
      return null;
    }

    void call() {
      calls.incrementAndGet();
    }

    int times() {
      return calls.get();
    }

    void reset() {
      calls.set(0);
      args.clear();
    }
  }

  // ---- createSignal.test.ts

  @Test void shouldStoreAndReturnValueOnRead() {
    SignalRef<Integer> x = rt.createSignal(1);
    assertEquals(1, x.get());
  }

  @Test void shouldUpdateSignalViaSetter() {
    SignalRef<Integer> x = rt.createSignal(1);
    x.set(2);
    assertEquals(1, x.get());
    rt.flush();
    assertEquals(2, x.get());
  }

  @Test void shouldUpdateSignalViaUpdateFunction() {
    SignalRef<Integer> x = rt.createSignal(1);
    x.update(n -> n + 1);
    assertEquals(1, x.get());
    rt.flush();
    assertEquals(2, x.get());
  }

  @Test void shouldAcceptEqualsOption() {
    SignalRef<Integer> x = rt.createSignal(1, new Runtime.Options<Integer>().equals((prev, next) -> prev + 1 == next));
    x.set(11);
    assertEquals(1, x.get());
    rt.flush();
    assertEquals(11, x.get());
    x.set(12);
    rt.flush();
    assertEquals(11, x.get());
    x.set(13);
    rt.flush();
    assertEquals(13, x.get());
    x.set(14);
    rt.flush();
    assertEquals(13, x.get());
  }

  @Test void shouldCreateSignalDerivedFromAnotherSignal() {
    SignalRef<Integer> x = rt.createSignal(1);
    SignalRef<Integer> y = rt.createDerivedSignal(prev -> x.get() + 1, null);
    assertEquals(2, y.get());
    y.set(1);
    rt.flush();
    assertEquals(1, y.get());
    x.set(2);
    rt.flush();
    assertEquals(3, y.get());
  }

  @Test void shouldCallUnobservedCallbackWhenSubscriberIsDisposed() {
    Fn unobserved = new Fn();
    SignalRef<Integer> x = rt.createSignal(1, new Runtime.Options<Integer>().unobserved(unobserved::call));
    Owner root = rt.createRoot(o -> {
      rt.createMemo(prev -> x.get());
      return o;
    });
    assertEquals(0, unobserved.times());
    root.dispose();
    assertEquals(1, unobserved.times());
    x.set(2);
    rt.flush();
    assertEquals(1, unobserved.times());
  }

  @Test void shouldCallUnobservedCallbackWhenSignalIsUnobserved() {
    Fn unobserved = new Fn();
    SignalRef<Boolean> x = rt.createSignal(true);
    SignalRef<Integer> y = rt.createSignal(1, new Runtime.Options<Integer>().unobserved(unobserved::call));
    rt.createRoot(o -> rt.createMemo(prev -> x.get() ? y.get() : null));
    assertEquals(0, unobserved.times());
    x.set(false);
    rt.flush();
    assertEquals(1, unobserved.times());
  }

  @Test void asyncComputedsFirstSettleDoesNotPermanentlyEntangleUnrelatedEffects() {
    List<Integer> values = new ArrayList<>();
    SignalRef<Integer> counter = rt.createRoot(o -> {
      SignalRef<Integer> c = rt.createSignal(0);
      SignalRef<String> os = rt.createDerivedSignal(prev -> new CompletableFuture<String>(), null);
      rt.<String>createRenderEffect(prev -> os.get(), (v, p) -> null);
      rt.createDerivedSignal(prev -> CompletableFuture.completedFuture(c.get()), null);
      rt.<Integer>createRenderEffect(prev -> c.get(), (v, p) -> {
        values.add(v);
        return null;
      });
      return c;
    });
    rt.flush();
    local.run();
    assertTrue(values.contains(0));
    counter.set(1);
    rt.flush();
    local.run();
    assertTrue(values.contains(1));
    counter.set(2);
    rt.flush();
    local.run();
    assertTrue(values.contains(2));
  }

  // ---- flush.test.ts

  @Test void balancesSyncDepthWhenAnEffectThrowsInsideFlushFn() {
    SignalRef<Integer> a = rt.createSignal(0);
    SignalRef<Integer> c = rt.createSignal(0);
    List<Integer> seenC = new ArrayList<>();
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> a.get(), (v, p) -> {
        if (v == 1) {
          throw new IllegalStateException("boom");
        }
        return null;
      });
      rt.<Integer>createEffect(prev -> c.get(), (v, p) -> {
        seenC.add(v);
        return null;
      });
      return null;
    });
    rt.flush();
    local.run();
    seenC.clear();
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> rt.flush(() -> a.set(1)));
    assertEquals("boom", e.getMessage());
    rt.scheduler.resetErrorHalt();
    c.set(3);
    local.run();
    assertEquals(List.of(3), seenC);
  }

  @Test void shouldBatchUpdates() {
    SignalRef<Integer> x = rt.createSignal(10);
    Fn effect = new Fn();
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> x.get(), effect::effect);
      return null;
    });
    rt.flush();
    x.set(20);
    x.set(30);
    x.set(40);
    assertEquals(1, effect.times());
    rt.flush();
    assertEquals(2, effect.times());
  }

  @Test void shouldNotFailIfCalledWhileFlushing() {
    SignalRef<Integer> a = rt.createSignal(10);
    Fn effect = new Fn();
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> a.get(), (v, p) -> {
        effect.call();
        rt.flush();
        return null;
      });
      return null;
    });
    rt.flush();
    assertEquals(1, effect.times());
    a.set(20);
    rt.flush();
    assertEquals(2, effect.times());
  }

  @Test void shouldRunCallbackAndFlushBeforeReturning() {
    SignalRef<Integer> x = rt.createSignal(10);
    Fn effect = new Fn();
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> x.get(), effect::effect);
      return null;
    });
    rt.flush();
    String result = rt.flush(() -> {
      x.set(20);
      assertEquals(1, effect.times());
      return "done";
    });
    assertEquals("done", result);
    assertEquals(2, effect.times());
  }

  @Test void nestedFlushFnDrainsAtEachLevel() {
    SignalRef<Integer> x = rt.createSignal(10);
    SignalRef<Integer> y = rt.createSignal(10);
    Fn effect = new Fn();
    rt.createRoot(o -> {
      rt.<List<Integer>>createEffect(prev -> List.of(x.get(), y.get()), effect::effect);
      return null;
    });
    rt.flush();
    rt.flush(() -> {
      x.set(20);
      assertEquals(1, effect.times());
      int inner = rt.flush(() -> {
        y.set(30);
        assertEquals(1, effect.times());
        return 1;
      });
      assertEquals(1, inner);
      assertEquals(2, effect.times());
      return null;
    });
    assertEquals(2, effect.times());
  }

  // ---- onCleanup.test.ts

  @Test void shouldBeInvokedWhenComputationIsDisposed() {
    Fn a = new Fn();
    Fn b = new Fn();
    Fn c = new Fn();
    Owner root = rt.createRoot(o -> {
      rt.createEffect(prev -> {
        rt.onCleanup(a::call);
        rt.onCleanup(b::call);
        rt.onCleanup(c::call);
        return null;
      }, (v, p) -> null);
      return o;
    });
    rt.flush();
    root.dispose();
    assertEquals(1, a.times());
    assertEquals(1, b.times());
    assertEquals(1, c.times());
  }

  @Test void shouldNotTriggerWrongOnCleanup() {
    Fn dispose = new Fn();
    rt.createRoot(o -> {
      rt.createEffect(prev -> {
        rt.onCleanup(dispose::call);
        return null;
      }, (v, p) -> null);
      Owner stop = rt.createRoot(inner -> {
        rt.createEffect(prev -> null, (v, p) -> null);
        return inner;
      });
      stop.dispose();
      rt.flush();
      assertEquals(0, dispose.times());
      return null;
    });
  }

  @Test void shouldCleanUpInReverseOrder() {
    List<String> order = new ArrayList<>();
    Owner root = rt.createRoot(o -> {
      rt.createEffect(prev -> {
        rt.onCleanup(() -> order.add("parent"));
        rt.createEffect(p -> {
          rt.onCleanup(() -> order.add("a"));
          return null;
        }, (v, p) -> null);
        rt.createEffect(p -> {
          rt.onCleanup(() -> order.add("b"));
          return null;
        }, (v, p) -> null);
        return null;
      }, (v, p) -> null);
      return o;
    });
    rt.flush();
    root.dispose();
    assertEquals(List.of("b", "a", "parent"), order);
  }

  @Test void cleanupOrderIsUnwindChildrenBeforeOwnerLaterRegistrationsBeforeEarlier() {
    List<String> order = new ArrayList<>();
    Owner root = rt.createRoot(o -> {
      rt.onCleanup(() -> order.add("A"));
      rt.onCleanup(() -> order.add("B"));
      rt.createRoot(inner -> rt.onCleanup(() -> order.add("C")));
      rt.createEffect(prev -> null, (v, p) -> () -> order.add("D"));
      return o;
    });
    rt.flush();
    root.dispose();
    assertEquals(List.of("D", "C", "B", "A"), order);
  }

  @Test void shouldDisposeAllRoots() {
    List<String> disposals = new ArrayList<>();
    Owner root = rt.createRoot(o -> {
      for (int s = 1; s <= 2; s++) {
        int subtree = s;
        rt.createRoot(inner -> {
          rt.onCleanup(() -> disposals.add("SUBTREE " + subtree));
          for (String name : List.of("A", "B", "C")) {
            rt.createEffect(prev -> {
              rt.onCleanup(() -> disposals.add("+" + name + subtree));
              return null;
            }, (v, p) -> null);
          }
          return null;
        });
      }
      rt.onCleanup(() -> disposals.add("ROOT"));
      return o;
    });
    rt.flush();
    root.dispose();
    assertEquals(List.of("+C2", "+B2", "+A2", "SUBTREE 2", "+C1", "+B1", "+A1", "SUBTREE 1", "ROOT"), disposals);
  }

  // ---- graph.test.ts

  @Test void shouldDropXBXUpdates() {
    SignalRef<Integer> x = rt.createSignal(2);
    Fn compute = new Fn();
    Accessor<String> c = rt.createRoot(o -> {
      Accessor<Integer> a = rt.createMemo(prev -> x.get() - 1);
      Accessor<Integer> b = rt.createMemo(prev -> x.get() + a.get());
      return rt.createMemo(prev -> {
        compute.call();
        return "c: " + b.get();
      });
    });
    assertEquals("c: 3", c.get());
    assertEquals(1, compute.times());
    compute.reset();
    x.set(4);
    rt.flush();
    assertEquals(1, compute.times());
  }

  @Test void shouldOnlyUpdateEverySignalOnceDiamondGraph() {
    SignalRef<String> x = rt.createSignal("a");
    Accessor<String> a = rt.createMemo(prev -> x.get());
    Accessor<String> b = rt.createMemo(prev -> x.get());
    Fn spy = new Fn();
    Accessor<String> c = rt.createMemo(prev -> {
      spy.call();
      return a.get() + " " + b.get();
    });
    assertEquals("a a", c.get());
    assertEquals(1, spy.times());
    x.set("aa");
    rt.flush();
    assertEquals("aa aa", c.get());
    assertEquals(2, spy.times());
  }

  @Test void shouldOnlyUpdateEverySignalOnceDiamondGraphAndTail() {
    SignalRef<String> x = rt.createSignal("a");
    Accessor<String> a = rt.createMemo(prev -> x.get());
    Accessor<String> b = rt.createMemo(prev -> x.get());
    Accessor<String> c = rt.createMemo(prev -> a.get() + " " + b.get());
    Fn spy = new Fn();
    Accessor<String> d = rt.createMemo(prev -> {
      spy.call();
      return c.get();
    });
    assertEquals("a a", d.get());
    assertEquals(1, spy.times());
    x.set("aa");
    rt.flush();
    assertEquals("aa aa", d.get());
    assertEquals(2, spy.times());
  }

  @Test void shouldBailOutIfResultIsTheSame() {
    SignalRef<String> x = rt.createSignal("a");
    Fn spy = new Fn();
    Accessor<String> b = rt.createRoot(o -> {
      Accessor<String> a = rt.createMemo(prev -> {
        x.get();
        return "foo";
      });
      return rt.createMemo(prev -> {
        spy.call();
        return a.get();
      });
    });
    assertEquals("foo", b.get());
    assertEquals(1, spy.times());
    x.set("aa");
    rt.flush();
    assertEquals("foo", b.get());
    assertEquals(1, spy.times());
  }

  // ---- createEffect.test.ts

  @Test void shouldRunEffect() {
    SignalRef<Integer> x = rt.createSignal(0);
    Fn compute = new Fn();
    Fn effect = new Fn();
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> {
        compute.call();
        return x.get();
      }, effect::effect);
      return null;
    });
    assertEquals(1, compute.times());
    assertEquals(0, effect.times());
    rt.flush();
    assertEquals(1, compute.times());
    assertEquals(1, effect.times());
    assertEquals(java.util.Arrays.asList(0, null), effect.args);
    x.set(1);
    rt.flush();
    assertEquals(2, compute.times());
    assertEquals(2, effect.times());
    assertEquals(List.of(1, 0), effect.args.subList(2, 4));
  }

  @Test void shouldRunEffectOnChange() {
    Fn effect = new Fn();
    SignalRef<Integer> x = rt.createSignal(10);
    SignalRef<Integer> y = rt.createSignal(10);
    Accessor<Integer> a = rt.createMemo(prev -> x.get() + y.get());
    Accessor<Integer> b = rt.createMemo(prev -> a.get());
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> b.get(), effect::effect);
      return null;
    });
    assertEquals(0, effect.times());
    x.set(20);
    rt.flush();
    assertEquals(1, effect.times());
    y.set(20);
    rt.flush();
    assertEquals(2, effect.times());
    x.set(20);
    y.set(20);
    rt.flush();
    assertEquals(2, effect.times());
  }

  @Test void shouldHandleNestedEffect() {
    SignalRef<Integer> x = rt.createSignal(0);
    SignalRef<Integer> y = rt.createSignal(0);
    Fn outer = new Fn();
    Fn inner = new Fn();
    Fn innerPureDispose = new Fn();
    Fn innerEffectDispose = new Fn();
    Owner stop = rt.createRoot(o -> {
      rt.<Object>createEffect(prev -> {
        x.get();
        rt.createEffect(p -> {
          y.get();
          rt.onCleanup(innerPureDispose::call);
          return null;
        }, (v, p) -> {
          inner.call();
          return innerEffectDispose::call;
        });
        return null;
      }, outer::effect);
      return o;
    });
    rt.flush();
    assertEquals(1, outer.times());
    assertEquals(1, inner.times());
    assertEquals(0, innerPureDispose.times());
    assertEquals(0, innerEffectDispose.times());

    y.set(1);
    rt.flush();
    assertEquals(1, outer.times());
    assertEquals(2, inner.times());
    assertEquals(1, innerPureDispose.times());
    assertEquals(1, innerEffectDispose.times());

    y.set(2);
    rt.flush();
    assertEquals(1, outer.times());
    assertEquals(3, inner.times());
    assertEquals(2, innerPureDispose.times());
    assertEquals(2, innerEffectDispose.times());

    inner.reset();
    innerPureDispose.reset();
    innerEffectDispose.reset();

    x.set(1);
    rt.flush();
    assertEquals(2, outer.times());
    assertEquals(1, inner.times(), "a new one is created");
    assertEquals(1, innerPureDispose.times());
    assertEquals(1, innerEffectDispose.times());

    y.set(3);
    rt.flush();
    assertEquals(2, outer.times());
    assertEquals(2, inner.times());
    assertEquals(2, innerPureDispose.times());
    assertEquals(2, innerEffectDispose.times());

    stop.dispose();
    x.set(10);
    y.set(10);
    assertEquals(2, outer.times());
    assertEquals(2, inner.times());
    assertEquals(3, innerPureDispose.times());
    assertEquals(3, innerEffectDispose.times());
  }

  @Test void shouldStopEffect() {
    Fn effect = new Fn();
    SignalRef<Integer> x = rt.createSignal(10);
    Owner stop = rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> x.get(), effect::effect);
      return o;
    });
    stop.dispose();
    x.set(20);
    rt.flush();
    assertEquals(0, effect.times());
  }

  @Test void shouldRunAllDisposalsBeforeEachNewRun() {
    Fn effect = new Fn();
    Fn a = new Fn();
    Fn b = new Fn();
    Fn c = new Fn();
    SignalRef<Integer> x = rt.createSignal(0);
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> {
        rt.onCleanup(a::call);
        rt.onCleanup(b::call);
        return x.get();
      }, (v, p) -> {
        effect.call();
        return c::call;
      });
      return null;
    });
    rt.flush();
    assertEquals(1, effect.times());
    assertEquals(0, a.times());
    assertEquals(0, b.times());
    assertEquals(0, c.times());
    for (int i = 1; i <= 3; i++) {
      x.set(i);
      rt.flush();
      assertEquals(i + 1, effect.times());
      assertEquals(i, a.times());
      assertEquals(i, b.times());
      assertEquals(i, c.times());
    }
  }

  @Test void shouldDisposeOfNestedEffect() {
    SignalRef<Integer> x = rt.createSignal(0);
    Fn inner = new Fn();
    Owner stop = rt.createRoot(o -> {
      rt.createEffect(prev -> {
        rt.<Integer>createEffect(p -> x.get(), inner::effect);
        return null;
      }, (v, p) -> null);
      return o;
    });
    stop.dispose();
    x.set(10);
    rt.flush();
    assertEquals(0, inner.times());
    assertFalse(inner.args.contains(10));
  }

  @Test void shouldConditionallyObserve() {
    SignalRef<Integer> x = rt.createSignal(0);
    SignalRef<Integer> y = rt.createSignal(0);
    SignalRef<Boolean> condition = rt.createSignal(true);
    Accessor<Integer> a = rt.createMemo(prev -> condition.get() ? x.get() : y.get());
    Fn effect = new Fn();
    rt.createRoot(o -> {
      rt.<Integer>createEffect(prev -> a.get(), effect::effect);
      return null;
    });
    rt.flush();
    assertEquals(1, effect.times());
    y.set(1);
    rt.flush();
    assertEquals(1, effect.times());
    x.set(1);
    rt.flush();
    assertEquals(2, effect.times());
    condition.set(false);
    rt.flush();
    assertEquals(2, effect.times());
    y.set(2);
    rt.flush();
    assertEquals(3, effect.times());
    x.set(3);
    rt.flush();
    assertEquals(3, effect.times());
  }

  @Test void shouldDisposeOfNestedConditionalEffect() {
    SignalRef<Boolean> condition = rt.createSignal(true);
    Fn a = new Fn();
    Fn b = new Fn();
    rt.createRoot(o -> {
      rt.createEffect(prev -> {
        Fn which = condition.get() ? a : b;
        rt.createEffect(p -> {
          rt.onCleanup(which::call);
          return null;
        }, (v, p) -> null);
        return null;
      }, (v, p) -> null);
      return null;
    });
    rt.flush();
    condition.set(false);
    rt.flush();
    assertEquals(1, a.times());
  }

  // ---- what ADR 0033 ported this for: a write that needs a load is held (F-2), and the frame is whole (F-1)

  @Test void aWriteThatNeedsALoadIsHeldAndRevealsWhole() {
    SignalRef<String> id = rt.createSignal("a");
    java.util.Map<String, CompletableFuture<String>> loads = new java.util.HashMap<>();
    loads.put("a", CompletableFuture.completedFuture("A"));
    List<String> screen = new ArrayList<>();
    rt.createRoot(o -> {
      Accessor<String> profile = rt.createMemo(prev -> loads.computeIfAbsent(id.get(), k -> new CompletableFuture<>()));
      rt.<String>createRenderEffect(prev -> id.get() + ":" + profile.get(), (v, p) -> {
        screen.add(v);
        return null;
      });
      return null;
    });
    rt.flush();
    assertEquals(List.of("a:A"), screen);

    id.set("b");
    rt.flush();
    local.run();
    assertEquals(List.of("a:A"), screen, "the screen keeps what it showed");
    assertEquals("a", rt.untrack(id::get));

    loads.get("b").complete("B");
    local.run();
    rt.flush();
    assertEquals(List.of("a:A", "b:B"), screen, "and the change and its load reveal as one frame");
    assertEquals("b", rt.untrack(id::get));
  }
}
