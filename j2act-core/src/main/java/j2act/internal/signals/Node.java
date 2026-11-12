package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import java.util.function.BiPredicate;
import java.util.function.IntConsumer;

/**
 * Port of Signal, Computed and Effect in @solidjs/signals core/types.ts and core/effect.ts at 9449845, as
 * one class. Solid's objects are shapes told apart by which fields they have, and its code reads
 * `(n as Computed)._statusFlags` on a plain signal to get undefined. A plain signal here has `fn == null`
 * and zeros in the rest, which answers the same.
 */
public final class Node extends Owner {

  /** A compute. May return a value, a CompletionStage or an AsyncIterable. */
  @FunctionalInterface
  public interface Compute {
    Object apply(Object prev) throws Exception;
  }

  /** An effect's second half; it may return a cleanup. */
  @FunctionalInterface
  public interface EffectFn {
    Runnable apply(Object value, Object prev) throws Exception;
  }

  /** An effect's error handler; the second argument runs its previous cleanup. */
  @FunctionalInterface
  public interface ErrorFn {
    void apply(Throwable error, Runnable cleanup) throws Exception;
  }

  /** Solid compares with `===`, which on JS strings and numbers is by value. Java's closest is equals. */
  static final BiPredicate<Object, Object> IS_EQUAL = java.util.Objects::equals;

  // ---- RawSignal
  Link subs;
  Link subsTail;
  Object value;
  /** Solid's `_equals`; null is its `false`, never equal. */
  BiPredicate<Object, Object> equals = IS_EQUAL;
  long time;
  /** Notify-epoch stamp of the last subscriber walk (NODE-SHAPE.md section 12d). */
  long notifiedAt = -1;
  Object pendingValue = Constants.NOT_PENDING;

  // ---- Computed
  Link deps;
  Link depsTail;
  int depGen;
  int statusFlags;
  int height;
  Node nextHeap;
  Node prevHeap;
  Compute fn;
  boolean loading;

  // ---- Effect
  int type;
  boolean modified;
  Object prevValue;
  EffectFn effectFn;
  ErrorFn errorFn;
  IntConsumer boundRunEffect;
  /** A tracked effect's run. */
  Runnable run;

  // ---- store slot node (CONFIG_SLOT_NODE, core.ts `slotSignal`): the target and key as back-refs
  Host host;
  Object key;
  boolean acc;
  /** The store's wrap cache: the view last served for this key and the raw it wrapped. */
  Object px;
  Object pxv;
  /** A derived store's family, on its derive (store/projection.ts). */
  Family fam;

  Node(Runtime rt) {
    super(rt);
  }

  /** What the core asks of a store target behind a slot node: Solid's `_host._fam`. */
  interface Host {
    Family fam();
  }

  /** A derived store's family as the core sees it: Solid's StoreFamily `node`, `st` and `live`. */
  interface Family {
    /** The projection computed. */
    Node node();

    /** A source's status reaching the derive by propagation; status 0 is Solid's call with none. */
    void st(int status, Throwable error);

    /** How many targets of the family carry nodes. */
    int live();

    /** Every property, presence and keys node of the live targets. */
    void eachLiveNode(java.util.function.Consumer<Node> each);
  }

  /** Link is Solid's `{_dep, _sub, ...}`. */
  static final class Link {
    final Node dep;
    final Node sub;
    Link nextDep;
    Link prevSub;
    Link nextSub;
    int gen;

    Link(Node dep, Node sub, Link nextDep, Link prevSub, int gen) {
      this.dep = dep;
      this.sub = sub;
      this.nextDep = nextDep;
      this.prevSub = prevSub;
      this.gen = gen;
    }
  }
}
