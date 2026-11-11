package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Port of @solidjs/signals store/target.ts at 9449845: what a view stands for. `v` is the committed backing,
 * a raw the application gave or a copy the store owns, and `pb` the pending backing of the current batch,
 * which is also what the container node `k` stages. Nodes are made when something subscribes.
 *
 * Left out, with what they serve in JavaScript: the prototype overlay (`ovl`, `del`), accessor properties
 * (`a`, `sc`, `kc`) and shallow stores (`s`).
 */
public final class StoreTarget implements Node.Host {
  /** Committed backing: the source object, shared, or an owned copy. */
  Object v;
  /** Pending backing for the current batch, null when settled. */
  Object pb;
  /** The committed backing is another store's view: this store is chained to it. */
  boolean ch;
  /** Live value-node count. */
  int nc;
  /** Per-property subscription nodes. */
  Map<Object, Node> n;
  /** Per-key presence nodes. */
  Map<Object, Node> h;
  /** The container node: membership, iteration and length subscriptions, and the backing's staging home. */
  Node k;
  /** Keys written by the current setter, with the batch's earlier setters' chained behind. */
  WrittenKeys wk;
  /** The deep witness: deep() subscribes one node per record. */
  Node dk;
  /** Parent target. */
  StoreTarget u;
  /** This target's key in the parent's backing. */
  Object pk;
  /** The view, stable for the target's life. */
  Object px;
  /** Sticky: a target with nodes exists below. */
  boolean d;
  /** Projection family, null for plain stores. */
  StoreFamily fam;
  BiPredicate<Object, Object> slotEquals;
  BiPredicate<Object, Object> containerEquals;

  StoreTarget() {
  }

  @Override public Node.Family fam() {
    return fam;
  }

  /** The view of this target. */
  public Object view() {
    return px;
  }

  /** The target of the store this one is part of. */
  public StoreTarget root() {
    StoreTarget t = this;
    while (t.u != null) {
      t = t.u;
    }
    return t;
  }

  /** The committed backing, for a shape choosing the view of a new target. */
  public Object backing() {
    return v;
  }

  /** One setter's written keys; `u` is the batch's earlier setters', the fold's union. */
  static final class WrittenKeys extends LinkedHashSet<Object> {
    private static final long serialVersionUID = 1L;
    transient WrittenKeys u;
  }

  /** Sticky descendants flag up the parent chain: adoption descends only where a target with nodes exists. */
  static void markDescendants(StoreTarget target) {
    for (StoreTarget t = target; t != null && !t.d; t = t.u) {
      t.d = true;
    }
  }

  /** Solid's StoreFamily: a derived store's targets, its derive and how its rows are keyed. */
  static final class StoreFamily implements Node.Family {
    /** Optimistic family: the setter's writes are guesses. */
    boolean opt;
    /** Row key, null when rows pair by position. */
    java.util.function.Function<Object, Object> key;
    /** raw to target for this family: one raw wraps once per family. */
    Map<Object, java.lang.ref.WeakReference<StoreTarget>> mapRefs;
    /** The projection computed. */
    Node node;
    /** Targets of the family that carry nodes. */
    final Set<StoreTarget> live = new LinkedHashSet<>();
    /** A source's status reaching the derive by propagation. */
    java.util.function.BiConsumer<Integer, Throwable> st;
    int run;
    boolean woke;
    int w;
    /** Targets carrying a lane value on one of their nodes; dropped once none is. */
    Set<StoreTarget> overlaid;
    /** The derive asked a new question over the family's guesses, stamped with that flush's clock. */
    long asked;

    @Override public Node node() {
      return node;
    }

    @Override public void st(int status, Throwable error) {
      if (st != null) {
        st.accept(status, error);
      }
    }

    @Override public int live() {
      return live.size();
    }

    @Override public void eachLiveNode(java.util.function.Consumer<Node> each) {
      for (StoreTarget t : new java.util.ArrayList<>(live)) {
        if (t.n != null) {
          new java.util.ArrayList<>(t.n.values()).forEach(each);
        }
        if (t.h != null) {
          new java.util.ArrayList<>(t.h.values()).forEach(each);
        }
        if (t.k != null) {
          each.accept(t.k);
        }
        if (t.dk != null) {
          each.accept(t.dk);
        }
      }
    }
  }
}
