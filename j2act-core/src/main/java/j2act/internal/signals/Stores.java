package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import j2act.internal.signals.StoreTarget.StoreFamily;
import j2act.internal.signals.StoreTarget.WrittenKeys;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Port of @solidjs/signals store/store.ts at 9449845: plain stores. A store is a tree of slot nodes and
 * nothing else carries reactive state: a leaf node per tracked or written key, presence nodes, the deep
 * witness, and the container node, whose value is the committed backing and whose staging is the pending
 * backing the draft writes. The committed backing is shared with the application until the first write
 * copies it and its ancestors, and changes at one moment, the flush's commit.
 *
 * A JS proxy's traps are the methods get, has, ownKeys, set and delete here, and what they ask of an object
 * goes through {@link StoreShape}. Left out with what JavaScript needs them for: the prototype overlay,
 * accessor properties and descriptors, shallow stores, and dev and observe output.
 */
public final class Stores {
  /** The container node's key, Solid's $TRACK. */
  static final Object TRACK = new Object() {
    @Override public String toString() {
      return "$TRACK";
    }
  };
  /** The deep witness's key. */
  static final Object DEEP = new Object() {
    @Override public String toString() {
      return "$DEEP";
    }
  };
  /** The written-keys bound is unusable: an array's length was written. */
  static final WrittenKeys WK_ALL = new WrittenKeys();

  static final String ASYNC_STORE_SETTER_MESSAGE =
    "[ASYNC_STORE_SETTER] Store setter callback returned a Promise. A store setter is a synchronous transaction: "
      + "the draft closes when the callback returns, so writes after an `await` are lost. "
      + "Move the await into an action() and call the setter from there.";

  /** store/hooks.ts OptHooks: what an optimistic store adds. Null until one is built. */
  interface OptHooks {
    Object draft(StoreTarget t);

    Object writes(StoreTarget t, Object pb);

    Object view(StoreTarget t, Object src, boolean writer);

    /** null is Solid's undefined: no guess. */
    Boolean has(StoreTarget t, Object key);

    /** NO_GUESS, null for a key guessed away, or the guessed value in a one-element array. */
    Object descriptor(StoreTarget t, Object key);

    void sweep();

    boolean arrangement(StoreTarget t, Object a, Object b);

    void reconcile(Object draft, Object incoming, Function<Object, Object> keyFn);

    void pair(StoreTarget t, Object pb);

    void ask(StoreFamily fam);

    void park(Transaction t);

    boolean stands(StoreTarget t);
  }

  /** store/hooks.ts AffectsHooks: affects() on a store. */
  interface AffectsHooks {
    void born(StoreTarget t, Node node, Object key);

    void witness(StoreTarget t, Object key);
  }

  /**
   * The container node's arrangement guess: the rows the setter left the container with, in order, as a List,
   * or for a record its keys, as a Set.
   */
  static final class LaneView {
    /** Solid's `g` left undefined: no re-base has run and none is owed. */
    static final Object UNSET = new Object();
    /** The rows the setter wrote, kept across re-bases; null until the first re-base on a guess written while the derive asked. */
    Object g = UNSET;
    Object rows;
    Object base;
    final Set<Object> removed;

    LaneView(Object rows, Object base, Set<Object> removed) {
      this.rows = rows;
      this.base = base;
      this.removed = removed;
    }
  }

  /** An optimistic store has no guess for a key. */
  static final Object NO_GUESS = new Object();

  private final Runtime rt;
  private StoreShape shape;
  /** raw to target for plain stores; a family has its own map, so one raw wraps once per family. */
  private Map<Object, WeakReference<StoreTarget>> storeLookup;
  /** Solid's $OWNER stamp: the backings the store allocated, each with its owning target. */
  private Map<Object, WeakReference<StoreTarget>> owned;
  OptHooks optHooks;
  AffectsHooks affectsHooks;
  /** A projection's draft writes carry it: the derive is the author (store/types.ts). */
  boolean writeOverride;

  /** Slot nodes whose last subscriber left while they still held a staging. */
  private final Set<Node> deferredReleases = new LinkedHashSet<>();
  private List<StoreTarget> foldList = new ArrayList<>();
  private List<Object> foldOlds = new ArrayList<>();
  private Map<StoreTarget, Integer> foldIndex;
  private boolean directCommit;
  private boolean userWrite;
  private boolean heldDerivationHit;
  private int writing;
  private Set<Object> writeScopes;
  private final Set<StoreTarget> pendingNotify = new LinkedHashSet<>();
  private final Map<Object, Map<Object, Boolean>> frameRaws = new IdentityHashMap<>();

  Stores(Runtime rt) {
    this.rt = rt;
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  /** The objects this runtime's stores hold. Set once, before the first store. */
  void install(StoreShape shape) {
    if (this.shape == shape) {
      return;
    }
    if (this.shape != null) {
      throw new IllegalStateException("a runtime's stores have one shape");
    }
    this.shape = shape;
    this.storeLookup = shape.identityMap();
    this.owned = shape.identityMap();
    Scheduler q = s();
    rt.graph.slotUnobservedHook = node -> {
      if (slotKept(node)) {
        deferredReleases.add(node);
      } else {
        releaseSlot(node);
      }
    };
    q.storeParkHook = this::storePark;
    q.storeCommitHook = () -> {
      drainFolds();
      if (optHooks != null) {
        optHooks.sweep();
      }
    };
    q.slotFlightHook = n -> {
      StoreFamily fam = n.host instanceof StoreTarget ? ((StoreTarget) n.host).fam : null;
      return fam != null && fam.node != null && (fam.node.statusFlags & STATUS_PENDING) != 0;
    };
    q.slotCoveredHook = n -> {
      // A chained link's committed value is the inner store's, whose commits it never learned of under a guess.
      StoreTarget t = (StoreTarget) n.host;
      if (t.ch) {
        Object key = n.key;
        Object inner = t.v;
        if (t.h != null && t.h.get(key) == n) {
          n.value = rt.core.untrack(() -> shape.has(inner, key));
        } else if (t.n != null && t.n.get(key) == n) {
          n.value = rt.core.untrack(() -> unwrapValue(shape.get(inner, key)));
        }
      }
      return n.value;
    };
    q.storeNodeHook = (t, key) -> getNode((StoreTarget) t, key);
    q.storeWrappableHook = shape::isWrappable;
  }

  StoreShape shape() {
    if (shape == null) {
      throw new IllegalStateException("no store shape: the runtime was given none");
    }
    return shape;
  }

  /** Solid's `value[$TARGET] !== undefined`: the value is a store's view. */
  boolean isView(Object v) {
    return v != null && shape.isObject(v) && shape.targetOf(v) != null;
  }

  boolean same(Object a, Object b) {
    return a == b || (a != null && b != null && shape.same(a, b));
  }

  private boolean isUndefined(Object v) {
    return v == shape.undefined() || (v != null && shape.undefined() != null && shape.same(v, shape.undefined()));
  }

  // ---- target.ts: lookup and ownership

  private static StoreTarget deref(WeakReference<StoreTarget> ref) {
    return ref != null ? ref.get() : null;
  }

  private Map<Object, WeakReference<StoreTarget>> lookupMap(StoreFamily fam) {
    if (fam == null) {
      return storeLookup;
    }
    if (fam.mapRefs == null) {
      fam.mapRefs = shape.identityMap();
    }
    return fam.mapRefs;
  }

  void setLookup(Object raw, StoreTarget t) {
    lookupMap(t.fam).put(raw, new WeakReference<>(t));
  }

  boolean isOwned(Object raw) {
    return deref(owned.get(raw)) != null;
  }

  /** The target wrapping raw in fam, or in the plain lookup. An owned raw answers from its stamp. */
  StoreTarget lookupTarget(Object raw, StoreFamily fam) {
    StoreTarget owner = deref(owned.get(raw));
    return owner != null && owner.fam == fam ? owner : deref(lookupMap(fam).get(raw));
  }

  // ---- wrap / dedupe

  private StoreTarget createTarget(Object value, StoreTarget parent, Object parentKey, StoreFamily fam) {
    StoreTarget t = new StoreTarget();
    t.v = value;
    t.ch = isView(value);
    t.u = parent;
    t.pk = parentKey;
    t.fam = fam;
    t.slotEquals = (a, b) -> same(a, b) || sameLogicalSlot(t, a, b);
    t.containerEquals = (a, b) -> same(a, b) || (optHooks != null && optHooks.arrangement(t, a, b));
    t.px = shape.view(t);
    setLookup(value, t);
    return t;
  }

  Object wrap(Object value, StoreTarget parent, Object parentKey) {
    return wrap(value, parent, parentKey, parent != null ? parent.fam : null);
  }

  Object wrap(Object value, StoreTarget parent, Object parentKey, StoreFamily fam) {
    StoreTarget existing = lookupTarget(value, fam);
    if (existing != null) {
      return existing.px;
    }
    StoreTarget t = shape.targetOf(value);
    if (t != null && same(t.px, value)) {
      if (fam == null || t.fam == fam) {
        return value;
      }
      // A view of another family wraps into this one, chained to it: writes stay apart.
      return createTarget(value, parent, parentKey, fam).px;
    }
    return createTarget(value, parent, parentKey, fam).px;
  }

  /** Unwrap our own views to their current backing; leave everything else. */
  Object unwrapValue(Object v) {
    if (v == null || !shape.isObject(v)) {
      return v;
    }
    StoreTarget t = shape.targetOf(v);
    if (t != null && (same(t.px, v) || lookupTarget(v, t.fam) == t) && t.v != null) {
      return t.pb != null ? t.pb : t.v;
    }
    return v;
  }

  // ---- nodes: pure subscription points

  static boolean sameKey(Object a, Object b) {
    return java.util.Objects.equals(a, b);
  }

  /** A container node's value as a backing: the base an arrangement guess is shown over, else the value. */
  static Object asBacking(Object v) {
    return v instanceof LaneView ? ((LaneView) v).base : v;
  }

  private boolean sameLogicalSlot(StoreTarget target, Object a, Object b) {
    if (a == null || b == null || !shape.isObject(a) || !shape.isObject(b)) {
      return false;
    }
    StoreTarget at = lookupTarget(a, target.fam);
    return at != null && at == lookupTarget(b, target.fam);
  }

  private void releaseSlot(Node node) {
    StoreTarget t = (StoreTarget) node.host;
    Object key = node.key;
    if (key == TRACK) {
      if (t.k == node) {
        t.k = null;
      }
    } else if (key == DEEP) {
      if (t.dk == node) {
        t.dk = null;
      }
    } else if (t.n != null && t.n.get(key) == node) {
      t.n.remove(key);
      t.nc--;
    } else if (t.h != null && t.h.get(key) == node) {
      t.h.remove(key);
    }
    if (t.fam != null && t.nc == 0 && t.k == null && t.dk == null && (t.h == null || t.h.isEmpty())) {
      t.fam.live.remove(t);
    }
  }

  private void noteNode(StoreTarget target, Node node, Object key) {
    if (target.fam != null) {
      target.fam.live.add(target);
    }
    StoreTarget.markDescendants(target);
    if (affectsHooks != null) {
      affectsHooks.born(target, node, key);
    }
  }

  /** A slot whose value has a staging, a lane value or a container the frame holds is kept. */
  private boolean slotKept(Node node) {
    StoreTarget host = (StoreTarget) node.host;
    return node.pendingValue != NOT_PENDING || (node.config & CONFIG_OVERRIDE) != 0
      || (host.k != null && (host.k.config & CONFIG_HELD) != 0);
  }

  /** A slot node born while its target has a staging takes it as its own. */
  private void bornStaged(StoreTarget target, Node node, Object staged, Object key) {
    if (node.equals != null && node.equals.test(node.value, staged)) {
      return;
    }
    Scheduler q = s();
    q.queuePendingNode(node);
    node.pendingValue = staged;
    Node k = target.k;
    if (k == null) {
      return;
    }
    if (q.laneStageHook != null && (k.config & (CONFIG_OVERRIDE | CONFIG_GUESS)) == CONFIG_OVERRIDE) {
      holdWithDerive(target, node, q.txOf(k));
    } else if ((k.config & CONFIG_HELD) != 0 && heldKeyChanged(target, key, true)) {
      Transaction l = q.txOf(k);
      if ((k.config & CONFIG_OVERRIDE) == 0 || l.folded != null) {
        q.holdNode(node, Scheduler.resolveTx(l.parent != null ? l.parent : l));
      }
    }
    if ((k.config & CONFIG_PROMOTED) != 0) {
      rt.core.notePromotedWrite(node);
    }
  }

  Node getNode(StoreTarget target, Object key) {
    if (target.n == null) {
      target.n = new SlotMap<>();
    }
    Node node = target.n.get(key);
    if (node == null) {
      Object pb = target.pb;
      node = rt.core.slotSignal(shape.get(committed(target), key), target.slotEquals, target, key, false);
      if (pb != null) {
        bornStaged(target, node, shape.get(pb, key), key);
      }
      target.n.put(key, node);
      target.nc++;
      noteNode(target, node, key);
    }
    return node;
  }

  Node getHasNode(StoreTarget target, Object key) {
    if (target.h == null) {
      target.h = new SlotMap<>();
    }
    Node node = target.h.get(key);
    if (node == null) {
      node = rt.core.slotSignal(shape.has(committed(target), key), Node.IS_EQUAL, target, key, false);
      Object pb = target.pb;
      if (pb != null) {
        bornStaged(target, node, shape.has(pb, key), key);
      }
      target.h.put(key, node);
      noteNode(target, node, key);
    }
    return node;
  }

  Node getContainerNode(StoreTarget target) {
    Node k = target.k;
    if (k == null) {
      k = target.k = rt.core.slotSignal(target.v, target.containerEquals, target, TRACK, false);
      noteNode(target, k, TRACK);
      if (target.pb != null && !directCommit) {
        k.value = preBatch(target);
        k.pendingValue = target.pb;
        s().queuePendingNode(k);
      }
    }
    return k;
  }

  /** The pending backing staged on the container node, when something could read the frame through it. */
  private void stageOn(StoreTarget target, Object pb) {
    Scheduler q = s();
    if (target.k == null && q.passLane == null && (rt.core.context == null || q.running) && !deriveHolds(target)) {
      return;
    }
    Node k = getContainerNode(target);
    if (k.pendingValue == NOT_PENDING) {
      q.queuePendingNode(k);
    }
    k.pendingValue = pb;
    if (rt.core.context != null) {
      rt.core.notePromotedWrite(k);
    }
    if (target.fam != null && !userWrite) {
      holdWithDerive(target, k, q.passLane);
    }
  }

  private boolean deriveHolds(StoreTarget target) {
    Node fw = target.fam != null ? target.fam.node : null;
    return fw != null && !userWrite && (fw.config & CONFIG_HELD) != 0;
  }

  private void storePark(Transaction t) {
    for (int i = 0; i < foldList.size(); i++) {
      StoreTarget target = foldList.get(i);
      if (target.k == null && target.pb != null) {
        s().holdNode(getContainerNode(target), t);
      }
    }
    if (optHooks != null) {
      optHooks.park(t);
    }
  }

  private Node getDeepNode(StoreTarget target) {
    Node dk = target.dk;
    if (dk == null) {
      dk = target.dk = rt.core.slotSignal(0, null, target, DEEP, false);
      noteNode(target, dk, DEEP);
    }
    return dk;
  }

  void bumpDeep(StoreTarget t) {
    if (t.dk != null) {
      rt.core.setSignal(t.dk, 1);
    }
  }

  private void notifyContainer(Node k, Object pb) {
    Scheduler q = s();
    if ((k.config & CONFIG_OVERRIDE) != 0) {
      rt.core.setSignal(k, pb);
    } else {
      if ((k.config & CONFIG_HELD) != 0) {
        q.joinFuture(q.txOf(k));
      }
      q.insertSubs(k);
      q.schedule();
    }
  }

  // ---- cloning

  /** A copy of source the store owns, stamped with its target. */
  Object cloneRaw(Object source, StoreTarget t) {
    Object clone = shape.clone(source);
    owned.put(clone, new WeakReference<>(t));
    return clone;
  }

  // ---- pending backing

  /** The backing the draft writes: a copy made at the first write of the batch. */
  private Object ensurePB(StoreTarget target) {
    Object pb = target.pb;
    if (optHooks != null && userWrite && target.fam != null && target.fam.opt && !pendingNotify.contains(target)) {
      return target.pb = optHooks.draft(target);
    }
    if (pb != null && !isOwned(pb)) {
      pb = target.pb = cloneRaw(pb, target);
      Node k = target.k;
      if (k != null && (k.config & CONFIG_HELD) == 0) {
        k.pendingValue = pb;
      }
    } else if (pb == null) {
      if (!directCommit) {
        queueFold(target);
      }
      pb = target.pb = cloneRaw(target.v, target);
      if (!directCommit) {
        stageOn(target, pb);
      }
    }
    return pb;
  }

  /** The committed backing and its ancestors' become the store's own: the path is copied once. */
  private void privatizeCommitted(StoreTarget target) {
    if (isOwned(target.v)) {
      return;
    }
    Object before = target.v;
    target.v = cloneRaw(before, target);
    target.ch = false;
    if (target.u != null) {
      privatizeCommitted(target.u);
      Object pv = target.u.v;
      Object slot = parentSlotKey(target, before);
      if (same(shape.get(pv, slot), before)) {
        shape.set(pv, slot, target.v);
      }
    }
  }

  /** The key this target sits at in its parent, followed when a row moved in an array. */
  private Object parentSlotKey(StoreTarget target, Object expected) {
    Object pk = target.pk;
    Object pv = target.u.v;
    Object at = shape.get(pv, pk);
    if (same(at, expected) || same(at, target.v) || !shape.isArray(pv)) {
      return pk;
    }
    int n = shape.length(pv);
    for (int i = 0; i < n; i++) {
      if (same(shape.get(pv, i), expected)) {
        target.pk = i;
        return i;
      }
    }
    return pk;
  }

  /** The adoption channel: incoming becomes the backing, with no copy and no write to what was given. */
  void adoptPB(StoreTarget target, Object incoming, boolean notify) {
    if (!directCommit) {
      queueFold(target);
    }
    Object base = target.pb != null ? target.pb : target.v;
    if (directCommit) {
      target.pb = null;
      if (target.k != null) {
        target.k.value = incoming;
      }
    } else {
      target.pb = incoming;
      stageOn(target, incoming);
    }
    target.v = incoming;
    target.ch = isView(incoming);
    target.wk = null;
    StoreTarget owner = deref(owned.get(incoming));
    if (owner != null && owner.fam == target.fam) {
      owned.put(incoming, new WeakReference<>(target));
    } else {
      setLookup(incoming, target);
    }
    if (notify && !same(base, incoming)) {
      notifyFold(target, base, incoming);
    }
  }

  // ---- fold: the commit of pending backings

  private void queueFold(StoreTarget target) {
    if (target.pb != null) {
      return;
    }
    s().schedule();
    if (foldIndex != null) {
      foldIndex.put(target, foldList.size());
    }
    foldList.add(target);
    foldOlds.add(target.v);
  }

  /** The backing a target had before this batch. */
  private Object preBatch(StoreTarget t) {
    if (foldIndex == null) {
      foldIndex = new IdentityHashMap<>();
      for (int i = 0; i < foldList.size(); i++) {
        foldIndex.put(foldList.get(i), i);
      }
    }
    Integer at = foldIndex.get(t);
    return at == null ? t.v : foldOlds.get(at);
  }

  private void drainFolds() {
    if (!foldList.isEmpty()) {
      List<StoreTarget> list = foldList;
      List<Object> olds = foldOlds;
      foldList = new ArrayList<>();
      foldOlds = new ArrayList<>();
      foldIndex = null;
      for (int i = 0; i < list.size(); i++) {
        StoreTarget t = list.get(i);
        if (t.pb == null) {
          continue;
        }
        Node k = t.k;
        if (k != null && k.pendingValue != NOT_PENDING) {
          foldList.add(t);
          foldOlds.add(olds.get(i));
        } else {
          foldTarget(t, olds.get(i));
        }
      }
    }
    if (!deferredReleases.isEmpty()) {
      for (Node node : new ArrayList<>(deferredReleases)) {
        if (node.subs == null && !slotKept(node)) {
          deferredReleases.remove(node);
          releaseSlot(node);
        }
      }
    }
  }

  /** Runs fn with commits made at once: a projection's creation run. */
  void runDirect(Runnable fn) {
    boolean was = directCommit;
    directCommit = true;
    try {
      fn.run();
    } finally {
      directCommit = was;
    }
  }

  private void copyKeys(Object to, Object from, Collection<Object> keys) {
    for (Object key : keys) {
      shape.set(to, key, shape.get(from, key));
    }
  }

  private void foldTarget(StoreTarget t, Object old) {
    Node k = t.k;
    Object pb = t.pb;
    if (pb != null) {
      if (same(pb, t.v)) {
        // adopted: the backing is in place
      } else if (!same(t.v, old)) {
        // The committed backing moved under this batch: its writes go onto it.
        privatizeCommitted(t);
        Object v = t.v;
        WrittenKeys wk = t.wk;
        if (wk != null && wk != WK_ALL) {
          for (WrittenKeys w = wk; w != null; w = w.u) {
            for (Object key : w) {
              if (shape.has(pb, key)) {
                shape.set(v, key, shape.get(pb, key));
              } else {
                shape.delete(v, key);
              }
            }
          }
        } else {
          copyKeys(v, pb, shape.keys(pb));
          if (shape.isArray(pb)) {
            shape.set(v, StoreShape.LENGTH, shape.length(pb));
          } else {
            for (Object key : shape.keys(old)) {
              if (!shape.has(pb, key)) {
                shape.delete(v, key);
              }
            }
          }
        }
      } else {
        t.v = pb;
        t.ch = isView(pb);
      }
      t.pb = null;
      t.wk = null;
    }
    if (k != null) {
      if (k.subs == null) {
        t.k = null;
      } else if (k.value instanceof LaneView) {
        ((LaneView) k.value).base = t.v;
      } else {
        k.value = t.v;
      }
    }
    if (!same(t.v, old) && t.u != null) {
      Object slot = parentSlotKey(t, old);
      if (same(shape.get(t.u.v, slot), old)) {
        privatizeCommitted(t.u);
        shape.set(t.u.v, slot, t.v);
      }
    }
  }

  // ---- write notification

  boolean userWriting() {
    return userWrite;
  }

  /** A user's write to a key a derived store's derive holds in another transaction. */
  private boolean heldDerivation(StoreTarget t, Node node, Object key) {
    if (t.fam == null || !userWrite) {
      return false;
    }
    Scheduler q = s();
    if (node != null) {
      return (node.config & (CONFIG_HELD | CONFIG_MANUAL_WRITE)) == CONFIG_HELD && q.txOf(node) != q.flushTransaction;
    }
    Node k = t.k;
    return k != null && (k.config & CONFIG_HELD) != 0 && q.txOf(k) != q.flushTransaction && heldKeyChanged(t, key, true);
  }

  /** The outermost setter's exit: one node write per changed observed key, so holds and lanes engage now. */
  private void notifyWrites(StoreTarget t) {
    Object pb = t.pb;
    if (pb == null) {
      return;
    }
    if (optHooks != null && t.fam != null && t.fam.opt) {
      if (userWrite) {
        t.pb = null;
        t.wk = null;
        t.pb = optHooks.writes(t, pb);
        return;
      }
      optHooks.pair(t, pb);
    }
    Object old = t.v;
    Map<Object, Node> nodes = t.n;
    WrittenKeys wk0 = t.wk;
    Collection<Object> writtenKeys = wk0 == WK_ALL ? null : wk0;
    if (t.fam != null && !heldDerivationHit && writtenKeys != null) {
      for (Object key : writtenKeys) {
        if (heldDerivation(t, nodes != null ? nodes.get(key) : null, key)) {
          heldDerivationHit = true;
          break;
        }
      }
    }
    if (nodes != null) {
      Collection<Object> keys = writtenKeys != null ? new ArrayList<>(writtenKeys) : new ArrayList<>(nodes.keySet());
      for (Object key : keys) {
        Node node = nodes.get(key);
        if (node == null) {
          continue;
        }
        rt.core.setSignal(node, shape.get(pb, key));
        if (t.fam != null) {
          if (!userWrite) {
            node.config &= ~CONFIG_MANUAL_WRITE;
            holdWithDerive(t, node, s().passLane);
          } else {
            node.config |= CONFIG_MANUAL_WRITE;
          }
        }
      }
    }
    Map<Object, Node> has = t.h;
    if (has != null) {
      Collection<Object> keys = writtenKeys != null ? new ArrayList<>(writtenKeys) : new ArrayList<>(has.keySet());
      for (Object key : keys) {
        Node node = has.get(key);
        if (node != null) {
          rt.core.setSignal(node, shape.has(pb, key));
          if (t.fam != null && !userWrite) {
            holdWithDerive(t, node, s().passLane);
          }
        }
      }
    }
    if (t.dk != null) {
      for (Object key : writtenKeys != null ? writtenKeys : shape.keys(pb)) {
        Object nv = shape.get(pb, key);
        Object ov = shape.get(old, key);
        if (nv != null && shape.isObject(nv) ? !targetsEqual(ov, nv) : !same(ov, nv)) {
          bumpDeep(t);
          break;
        }
      }
    }
    Node k = t.k;
    if (k != null && (k.subs != null || (k.config & CONFIG_OVERRIDE) != 0)) {
      boolean changed = shape.isArray(pb) && shape.isArray(old) ? arrayStructureChanged(old, pb) : membershipChanged(old, pb);
      if (changed) {
        notifyContainer(k, pb);
      }
    }
  }

  boolean targetsEqual(Object ov, Object nv) {
    if (ov == null || nv == null || !shape.isObject(ov) || !shape.isObject(nv)) {
      return false;
    }
    StoreTarget ot = lookupTarget(ov, null);
    return ot != null && ot == lookupTarget(nv, null);
  }

  boolean arrayStructureChanged(Object old, Object neu) {
    int n = shape.length(neu);
    if (shape.length(old) != n) {
      return true;
    }
    for (int i = 0; i < n; i++) {
      Object ov = shape.get(old, i);
      Object nv = shape.get(neu, i);
      if (!same(ov, nv) && !targetsEqual(ov, nv)) {
        return true;
      }
    }
    return false;
  }

  boolean membershipChanged(Object old, Object neu) {
    List<Object> nk = shape.keys(neu);
    if (shape.keys(old).size() != nk.size()) {
      return true;
    }
    for (Object key : nk) {
      if (!shape.has(old, key)) {
        return true;
      }
    }
    return false;
  }

  void notifyKeyDiff(Node node, Object key, Object old, Object neu) {
    Object ov = shape.get(old, key);
    Object nv = shape.get(neu, key);
    if (isView(old) || (!same(ov, nv) && !targetsEqual(ov, nv))) {
      rt.core.setSignal(node, nv);
    }
  }

  void notifyKeyValue(Node node, Object ov, Object nv) {
    if (!same(ov, nv) && !targetsEqual(ov, nv)) {
      rt.core.setSignal(node, nv);
    }
  }

  void notifyFoldTail(StoreTarget t, Object old, Object neu) {
    Map<Object, Node> has = t.h;
    // A chained backing being left: what it showed is not what `old` reads now, so everything is told.
    boolean live = isView(old);
    if (has != null) {
      for (Object key : new ArrayList<>(has.keySet())) {
        if (live || shape.has(old, key) != shape.has(neu, key)) {
          rt.core.setSignal(has.get(key), shape.has(neu, key));
        }
      }
    }
    Node k = t.k;
    if (k != null && (k.subs != null || (k.config & CONFIG_OVERRIDE) != 0)) {
      boolean changed = live
        || (shape.isArray(neu) && shape.isArray(old) ? arrayStructureChanged(old, neu) : membershipChanged(old, neu));
      if (changed) {
        notifyContainer(k, neu);
      }
    }
  }

  void notifyFold(StoreTarget t, Object old, Object neu) {
    if (t.dk != null && !same(old, neu)) {
      bumpDeep(t);
    }
    Map<Object, Node> nodes = t.n;
    if (nodes != null) {
      for (Object key : new ArrayList<>(nodes.keySet())) {
        notifyKeyDiff(nodes.get(key), key, old, neu);
      }
    }
    notifyFoldTail(t, old, neu);
  }

  // ---- read paths

  /** What a setter's scope is keyed by: the family, or the plain store's root. */
  private Object scopeKey(StoreTarget target) {
    if (target.fam != null) {
      return target.fam;
    }
    StoreTarget t = target;
    while (t.u != null) {
      t = t.u;
    }
    return t;
  }

  boolean inDraft(StoreTarget target) {
    return writeScopes != null && writeScopes.contains(scopeKey(target));
  }

  /** A view served inside a draft joins the draft's scope: a store placed in another is written through it. */
  private Object draftServe(StoreTarget target, Object view) {
    if (writeScopes != null && inDraft(target)) {
      StoreTarget ct = shape.targetOf(view);
      if (ct != null && ct.v != null) {
        writeScopes.add(scopeKey(ct));
      }
    }
    return view;
  }

  /** The backing committed readers see: the one before this batch. */
  Object committed(StoreTarget t) {
    Node k = t.k;
    if (k != null) {
      return k.pendingValue != NOT_PENDING ? asBacking(k.value) : t.v;
    }
    return t.pb != null ? preBatch(t) : t.v;
  }

  /** The computation a staging is read for: the running one, a root's parent, never a forbidden scope. */
  private Node stagingReader() {
    Owner c = rt.core.context;
    if (c == null) {
      return null;
    }
    if (c.root) {
      c = c.parentComputed;
    }
    return c == null || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0 || !(c instanceof Node) ? null : (Node) c;
  }

  /** The backing a read is served from: the draft's own staging, a pass's staging, or the committed one. */
  private Object readSource(StoreTarget target, Object key, boolean hasKey, boolean shapeRead) {
    Scheduler q = s();
    if (userWrite && target.fam != null && target.fam.opt && !pendingNotify.contains(target) && inDraft(target)) {
      Object draft = ensurePB(target);
      pendingNotify.add(target);
      return draft;
    }
    Object pb = target.pb;
    if (pb == null) {
      return target.v;
    }
    if (inDraft(target) || writeOverride) {
      if (writeOverride && !userWrite) {
        Node leaf = hasKey && target.n != null ? target.n.get(key) : null;
        if (leaf != null) {
          if ((leaf.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD) {
            q.joinPassTx(q.txOf(leaf), false);
          }
        } else {
          Node k = target.k;
          if (k != null && (k.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD
            && (!hasKey || heldKeyChanged(target, key, true))) {
            q.joinPassTx(q.txOf(k), false);
          }
        }
      }
      return pb;
    }
    Node k = target.k;
    if (hasKey) {
      Node leaf = target.n != null ? target.n.get(key) : null;
      if (leaf != null && (leaf.config & CONFIG_OVERRIDE) != 0) {
        return committed(target);
      }
    }
    boolean held = k != null && (k.config & CONFIG_HELD) != 0;
    if (held && hasKey && !heldKeyChanged(target, key, shapeRead)) {
      return pb;
    }
    Node c = stagingReader();
    if (c == null) {
      if (rt.owners.getObserver() != null) {
        return pb;
      }
      if (familyAhead(target)) {
        return pb;
      }
      if (rt.core.verdict == null || (!held && hasKey && !keyChanged(target, key, shapeRead))) {
        return held ? asBacking(k.value) : target.v;
      }
      return k == null ? committed(target) : asBacking(rt.core.read(k));
    }
    if (!held) {
      if ((c.flags & REACTIVE_STAGED_READ) != 0 && (c.config & (CONFIG_VERDICT | CONFIG_CHILDREN_FORBIDDEN)) == 0) {
        return pb;
      }
      if (hasKey && !keyChanged(target, key, shapeRead)) {
        return committed(target);
      }
      if (k == null) {
        if ((c.config & CONFIG_VERDICT) != 0 && rt.core.stagedScreen(c, null)) {
          return committed(target);
        }
        rt.core.stagedRead(c, null);
        return pb;
      }
    }
    Node kk = k;
    return asBacking(rt.core.untrack(() -> rt.core.read(kk)));
  }

  private Object readSource(StoreTarget target) {
    return readSource(target, null, false, false);
  }

  private boolean heldKeyChanged(StoreTarget target, Object key, boolean shapeRead) {
    return changedBetween(target, target.k.value, target.k.pendingValue, key) && !adoptChild(target, key);
  }

  private Map<Object, Boolean> rawsOf(Object frame) {
    Map<Object, Boolean> raws = frameRaws.get(frame);
    if (raws == null) {
      raws = shape.identityMap();
      for (Object key : shape.keys(frame)) {
        Object v = shape.get(frame, key);
        if (v != null && shape.isObject(v)) {
          raws.put(v, Boolean.TRUE);
        }
      }
      frameRaws.put(frame, raws);
    }
    return raws;
  }

  boolean sameRow(Object pv, Object nv, Function<Object, Object> keyFn) {
    return pv != null && shape.isObject(pv) && nv != null && shape.isObject(nv)
      && (keyFn == null || sameKey(keyFn.apply(pv), keyFn.apply(nv)));
  }

  /** A row first read during a held adoption is paired then, as the adoption would have paired it. */
  private boolean adoptChild(StoreTarget target, Object key) {
    Node k = target.k;
    Object old = asBacking(k.value);
    Object pending = k.pendingValue;
    Object ov = shape.get(old, key);
    Object nv = shape.get(pending, key);
    boolean keyed = shape.isArray(old);
    if ((keyed && target.fam == null) || !sameRow(ov, nv, keyed ? target.fam.key : null)
      || !shape.isWrappable(ov) || !shape.isWrappable(nv) || shape.isArray(ov) != shape.isArray(nv)
      || lookupTarget(ov, target.fam) != null || lookupTarget(nv, target.fam) != null
      || rawsOf(old).containsKey(nv) || rawsOf(pending).containsKey(ov)) {
      return false;
    }
    StoreTarget ct = shape.targetOf(wrap(ov, target, key));
    if (ct == null || !same(ct.v, ov)) {
      return false;
    }
    adoptPB(ct, nv, false);
    s().holdNode(getContainerNode(ct), s().txOf(k));
    return true;
  }

  private boolean familyAhead(StoreTarget target) {
    return target.fam != null && rt.core.context == null && !(target.k != null && (target.k.config & CONFIG_HELD) != 0);
  }

  private boolean keyChanged(StoreTarget target, Object key, boolean shapeRead) {
    return changedBetween(target, committed(target), target.pb, key);
  }

  private boolean changedBetween(StoreTarget target, Object v, Object pb, Object key) {
    if (target.ch || isView(pb) || !shape.sameKind(pb, v)) {
      return true;
    }
    boolean inNew = shape.has(pb, key);
    if (inNew != shape.has(v, key)) {
      return true;
    }
    if (!inNew) {
      return false;
    }
    Object nv = shape.get(pb, key);
    Object ov = shape.get(v, key);
    return !same(ov, nv) && !sameLogicalSlot(target, ov, nv);
  }

  private Object nodeValue(Node node, StoreTarget target) {
    Owner context = rt.core.context;
    if (node.pendingValue != NOT_PENDING && (node.config & CONFIG_HELD) == 0 && target.fam != null
      && (context == null || (target.fam.node != null && (target.fam.node.statusFlags & STATUS_UNINITIALIZED) != 0))) {
      if (context != null && rt.core.tracking) {
        rt.graph.link(node, (Node) context);
      }
      return node.pendingValue;
    }
    return rt.core.read(node);
  }

  private Object serveDataKey(StoreTarget target, Object key, Object backingValue, Object src, Node node) {
    boolean chained = target.ch && same(src, target.v);
    Object v = backingValue;
    if (!inDraft(target) && !writeOverride) {
      if (node == null && rt.owners.getObserver() != null) {
        node = getNode(target, key);
      }
      if (node != null) {
        Object nv = nodeValue(node, target);
        if (chained) {
          // The link follows the inner store: its value is what was just read through it.
          node.value = backingValue;
          if ((node.config & CONFIG_OVERRIDE) != 0) {
            v = nv;
          }
        } else {
          v = nv;
        }
      }
    }
    if (target.ch && !chained && v != null && shape.isObject(v)) {
      StoreTarget vt = shape.targetOf(v);
      if (vt == null || !same(vt.px, v)) {
        v = resolveChainedRaw(target, key, v);
      }
    }
    if (node != null) {
      if (v != null && node.pxv != null && same(node.pxv, v) && !isUndefined(v)) {
        return draftServe(target, node.px);
      }
      if (!shape.isWrappable(v)) {
        return v;
      }
      Object p = wrap(v, target, key);
      node.px = p;
      node.pxv = v;
      return draftServe(target, p);
    }
    if (!shape.isWrappable(v)) {
      return v;
    }
    return draftServe(target, wrap(v, target, key));
  }

  /** A raw read from a chained target's own staging, as the inner store's view of it when it has one. */
  Object resolveChainedRaw(StoreTarget target, Object key, Object v) {
    StoreTarget innerT = shape.targetOf(target.v);
    if (innerT == null) {
      return v;
    }
    if (innerT.ch) {
      Object iv = resolveChainedRaw(innerT, key, v);
      return same(iv, v) ? v : wrap(iv, innerT, key);
    }
    StoreTarget ownedBy = lookupTarget(v, innerT.fam);
    if (ownedBy != null) {
      return ownedBy.px;
    }
    if ((same(shape.get(innerT.v, key), v) || (innerT.pb != null && same(shape.get(innerT.pb, key), v))) && shape.isWrappable(v)) {
      return wrap(v, innerT, key);
    }
    return v;
  }

  static boolean linkedTo(Node c, Node n) {
    for (Link s = n.subs; s != null; s = s.nextSub) {
      if (s.sub == c) {
        return true;
      }
    }
    return false;
  }

  /** A read through a derived store pulls its derive first: the firewall. */
  private void pullFamily(StoreTarget target) {
    Node fw = target.fam.node;
    if (fw == null || writeOverride || inDraft(target)) {
      return;
    }
    Scheduler q = s();
    if ((fw.statusFlags & (STATUS_PENDING | STATUS_ERROR)) != 0 || (fw.config & CONFIG_STAGED_ERROR) != 0) {
      if (rt.core.verdict == null && (fw.statusFlags & STATUS_PENDING) != 0 && optHooks != null && optHooks.stands(target)) {
        return;
      }
      Owner c = rt.core.context;
      if (c instanceof Node && ((Node) c).type == EFFECT_RENDER && (fw.statusFlags & STATUS_PENDING) != 0
        && (fw.config & CONFIG_HELD) != 0 && q.flushTransaction != q.txOf(fw) && !linkedTo((Node) c, fw)) {
        return;
      }
      if (c instanceof Node && (fw.config & CONFIG_STAGED_ERROR) != 0) {
        rt.graph.link(fw, (Node) c);
      }
      rt.core.read(fw);
    } else {
      rt.core.untrack(() -> rt.core.read(fw));
      if (q.observeFlightHook != null && (fw.config & CONFIG_VERDICT) != 0 && (fw.flags & (EFFECT_RENDER << 21)) == 0
        && rt.core.tracking) {
        StoreFamily fam = target.fam;
        fam.w = 0;
        for (Link l = fw.deps; l != null; l = l.nextDep) {
          if ((l.dep.statusFlags & STATUS_PENDING) != 0) {
            fam.w = 1;
            rt.core.read(l.dep);
          }
        }
      }
    }
  }

  /** A derive's write is its lane's derivation, or held with the derive when the derive is held. */
  private void holdWithDerive(StoreTarget target, Node node, Transaction lane) {
    Scheduler q = s();
    Node fw = target.fam != null ? target.fam.node : null;
    if (q.laneStageHook != null && lane != null && node.pendingValue != NOT_PENDING && (node.config & CONFIG_OVERRIDE) == 0) {
      q.list(node, lane);
      node.x.lane = node.pendingValue;
      node.pendingValue = NOT_PENDING;
      node.config |= CONFIG_OVERRIDE;
      return;
    }
    if (fw != null && (fw.config & CONFIG_HELD) != 0 && node.pendingValue != NOT_PENDING
      && (node.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == 0) {
      q.holdNode(node, q.txOf(fw));
      node.x.reask = fw.x.reask;
    }
  }

  private Object enumerationSource(StoreTarget target) {
    if (!inDraft(target) && !writeOverride && rt.owners.getObserver() != null) {
      Object base = asBacking(rt.core.read(getContainerNode(target)));
      return optHooks != null && target.fam != null && target.fam.opt ? optHooks.view(target, base, false) : base;
    }
    Object src = readSource(target);
    return optHooks != null && optRead(target) ? optHooks.view(target, src, false) : src;
  }

  private boolean optRead(StoreTarget target) {
    return target.fam != null && target.fam.opt && !inDraft(target) && !writeOverride;
  }

  // ---- the proxy's traps

  private void touch(StoreTarget target, Object key) {
    WrittenKeys wk = target.wk;
    if (pendingNotify.add(target)) {
      if (wk != null && wk != WK_ALL) {
        wk = new WrittenKeys();
        wk.u = target.wk;
        target.wk = wk;
      }
    }
    if (wk != WK_ALL) {
      if (target.wk == null) {
        target.wk = new WrittenKeys();
      }
      target.wk.add(key);
    }
  }

  private void witness(StoreTarget target, Object key) {
    if (rt.core.verdict != null && affectsHooks != null && rt.owners.getObserver() == null) {
      affectsHooks.witness(target, key);
    }
  }

  /** The get trap: the value at key, a view when it is an object the store wraps. */
  Object get(StoreTarget target, Object key) {
    witness(target, key);
    if (target.fam != null) {
      pullFamily(target);
    }
    Node node0 = target.n != null ? target.n.get(key) : null;
    if (writeScopes == null && !writeOverride && node0 != null && (!target.ch || (node0.config & CONFIG_OVERRIDE) != 0)) {
      Object nv = nodeValue(node0, target);
      if (nv == null || !shape.isObject(nv)) {
        return nv;
      }
      if (node0.pxv != null && same(node0.pxv, nv)) {
        return node0.px;
      }
      if (shape.isWrappable(nv)) {
        Object p = wrap(nv, target, key);
        node0.px = p;
        node0.pxv = nv;
        return p;
      }
      return nv;
    }
    Object src = readSource(target, key, true, false);
    Object v = shape.get(src, key);
    if (isUndefined(v) && !shape.has(src, key)) {
      if (target.ch && same(src, target.v)) {
        return serveDataKey(target, key, v, src, node0);
      }
      if (!inDraft(target)) {
        if (rt.owners.getObserver() != null) {
          getNode(target, key);
        }
        Node node = target.n != null ? target.n.get(key) : null;
        if (node != null) {
          Object nv = nodeValue(node, target);
          return shape.isWrappable(nv) ? draftServe(target, wrap(nv, target, key)) : nv;
        }
      }
      return v;
    }
    return serveDataKey(target, key, v, src, node0);
  }

  /** The has trap: presence of a key, tracked apart from its value. */
  boolean has(StoreTarget target, Object key) {
    witness(target, key);
    if (target.fam != null) {
      pullFamily(target);
    }
    Object src = readSource(target, key, true, false);
    if (!inDraft(target) && !writeOverride) {
      if (rt.owners.getObserver() != null) {
        Node node = getHasNode(target, key);
        Object nv = rt.core.read(node);
        if (!target.ch || !same(src, target.v)) {
          return Signals.truthy(nv);
        }
        boolean present = shape.has(src, key);
        node.value = present;
        return (node.config & CONFIG_OVERRIDE) != 0 ? Signals.truthy(nv) : present;
      } else if (optHooks != null && optRead(target)) {
        Boolean guessed = optHooks.has(target, key);
        if (guessed != null) {
          return guessed;
        }
      }
    }
    return shape.has(src, key);
  }

  /** The ownKeys trap: the keys, tracked as membership. */
  List<Object> ownKeys(StoreTarget target) {
    witness(target, null);
    if (target.fam != null) {
      pullFamily(target);
    }
    return shape.keys(enumerationSource(target));
  }

  /**
   * The getOwnPropertyDescriptor trap: the raw value at key in a one-element array, or null when the key is not
   * there. A reader that already holds the container, as an enumeration does, reads through it and makes no
   * presence node.
   */
  Object[] descriptor(StoreTarget target, Object key) {
    Owner obs = rt.owners.getObserver();
    witness(target, key);
    if (target.fam != null) {
      pullFamily(target);
    }
    boolean plain = obs != null && !inDraft(target) && !writeOverride;
    Object src;
    if (plain && observerHoldsContainer(target, obs)) {
      src = enumerationSource(target);
    } else if (plain) {
      Node node = getHasNode(target, key);
      boolean present = Signals.truthy(rt.core.read(node));
      src = readSource(target, key, true, true);
      if (!present && (!target.ch || (node.config & CONFIG_OVERRIDE) != 0 || !shape.has(src, key))) {
        return null;
      }
    } else {
      src = readSource(target, key, true, true);
    }
    if (optHooks != null && !(plain && observerHoldsContainer(target, obs)) && optRead(target)) {
      Object guessed = optHooks.descriptor(target, key);
      if (guessed != NO_GUESS) {
        return (Object[]) guessed;
      }
    }
    return shape.has(src, key) ? new Object[] {shape.get(src, key)} : null;
  }

  /** The observer's latest dependency of this pass is the container: it is enumerating. */
  private boolean observerHoldsContainer(StoreTarget target, Owner obs) {
    Node k = target.k;
    if (k == null) {
      return false;
    }
    Link l = k.subsTail;
    return l != null && l.sub == obs
      && ((obs.flags & REACTIVE_RECOMPUTING_DEPS) == 0 || l.gen == ((Node) obs).depGen);
  }

  /** An array's length, read as its own key as in Solid. */
  int length(StoreTarget target) {
    Object n = get(target, StoreShape.LENGTH);
    return n instanceof Number ? ((Number) n).intValue() : shape.length(readSource(target));
  }

  /** Solid's store[$TRACK]: subscribes to membership and order. */
  void track(StoreTarget target) {
    if (target.fam != null) {
      pullFamily(target);
    }
    if (!inDraft(target) && rt.owners.getObserver() != null) {
      rt.core.read(getContainerNode(target));
      Object src = readSource(target);
      if (isView(src)) {
        track(shape.targetOf(src));
      }
    }
  }

  /** The set trap. Outside the setter's draft a write is ignored: a store is read-only there. */
  void set(StoreTarget target, Object key, Object value) {
    boolean draft = inDraft(target);
    if (!draft && !writeOverride) {
      return;
    }
    Object uv = unwrapValue(value);
    Object pb = ensurePB(target);
    if (userWrite && target.k != null && (target.k.config & CONFIG_HELD) != 0) {
      deferredReleases.add(getNode(target, key));
    }
    if (shape.isArray(pb)) {
      touch(target, key);
      if (key == StoreShape.LENGTH) {
        target.wk = WK_ALL;
      } else {
        touch(target, StoreShape.LENGTH);
      }
    } else {
      touch(target, key);
    }
    shape.set(pb, key, uv);
    if (!draft) {
      notifyWrites(target);
    }
  }

  /** The deleteProperty trap. */
  void delete(StoreTarget target, Object key) {
    boolean draft = inDraft(target);
    if (!draft && !writeOverride) {
      return;
    }
    Object pb = ensurePB(target);
    touch(target, key);
    if (shape.has(pb, key)) {
      shape.delete(pb, key);
    }
    if (!draft) {
      notifyWrites(target);
    }
  }

  // ---- setter and creation

  /** Solid's storeSetter: fn writes the draft, and its writes are notified when the outermost setter returns. */
  void storeSetter(Object view, Function<Object, Object> fn, boolean guard) {
    Owner context = rt.core.context;
    if (guard && context != null && (context.config & CONFIG_CHILDREN_FORBIDDEN) == 0 && rt.core.disposalDepth == 0) {
      throw new IllegalStateException(Core.REACTIVE_WRITE_IN_OWNED_SCOPE_SIGNAL_MESSAGE);
    }
    StoreTarget target = shape.targetOf(view);
    Set<Object> prevScopes = writeScopes;
    writeScopes = Collections.newSetFromMap(new IdentityHashMap<>());
    writeScopes.add(scopeKey(target));
    boolean prevUser = userWrite;
    userWrite = guard;
    if (writing == 0 && !pendingNotify.isEmpty()) {
      pendingNotify.clear();
    }
    writing++;
    Object result;
    try {
      result = fn.apply(view);
    } finally {
      writing--;
      writeScopes = prevScopes;
      try {
        if (writing == 0 && !pendingNotify.isEmpty()) {
          List<StoreTarget> touched = new ArrayList<>(pendingNotify);
          pendingNotify.clear();
          for (StoreTarget t : touched) {
            notifyWrites(t);
          }
          if (directCommit) {
            for (StoreTarget t : touched) {
              if (t.pb != null) {
                foldTarget(t, t.v);
              }
            }
          }
        }
      } finally {
        userWrite = prevUser;
      }
    }
    if (guard && result instanceof java.util.concurrent.CompletionStage) {
      throw new IllegalStateException(ASYNC_STORE_SETTER_MESSAGE);
    }
    if (result != null && !same(result, view) && shape.isWrappable(result)) {
      if (optHooks != null && guard && target.fam != null && target.fam.opt && !writeOverride) {
        boolean was = userWrite;
        userWrite = guard;
        try {
          optHooks.writes(target, unwrapValue(result));
        } finally {
          userWrite = was;
        }
      } else {
        adoptPB(target, unwrapValue(result), true);
      }
    }
    if (guard && heldDerivationHit) {
      heldDerivationHit = false;
      Node fw = target.fam.node;
      if ((fw.flags & REACTIVE_DIRTY) == 0) {
        fw.flags = (fw.flags & ~REACTIVE_CHECK) | REACTIVE_DIRTY;
        s().dirtyQueue.insertIntoHeap(fw);
        s().schedule();
      }
    }
  }

  /** Solid's createStore(initialValue): the view of a store over value. */
  Object createStore(Object value) {
    shape();
    return wrap(value, null, null, null);
  }

  // ---- reconcile (store/reconcile.ts): the immutable-diff adoption channel

  /**
   * Port of store/reconcile.ts: adopts incoming as the backing at every level that has a view, with no copy
   * and no write to what was given, notifies only where nodes exist, and descends into a changed pair only
   * where something below is listened to. Rows of a keyed array match by key from the first misalignment,
   * so a row that survives keeps its view.
   */
  void reconcileState(Object value, Object state, Function<Object, Object> keyFn, boolean replace) {
    StoreTarget t = state != null && shape.isObject(state) ? shape.targetOf(state) : null;
    if (state == null) {
      throw new IllegalStateException("Cannot reconcile null or undefined state");
    }
    if (t == null || !same(t.px, state)) {
      throw new IllegalStateException("reconcile target is not a store proxy");
    }
    // A user's reconcile on an optimistic family is an optimistic edit: the keyed diff is written into the draft.
    if (optHooks != null && userWrite && t.fam != null && t.fam.opt) {
      optHooks.reconcile(state, unwrapValue(value), keyFn);
      return;
    }
    // Replace-mode root handed another store's view: chain to it.
    if (replace && !same(value, state) && isView(value)) {
      Object prev = t.pb != null ? t.pb : t.v;
      if (!same(prev, value)) {
        adoptPB(t, value, true);
      }
      return;
    }
    Object incoming = unwrapValue(value);
    if (keyFn != null) {
      Object prev = t.pb != null ? t.pb : t.v;
      Object eq = keyFn.apply(prev);
      if (eq != null && !sameKey(keyFn.apply(incoming), eq)) {
        if (!replace) {
          throw new IllegalStateException("Cannot reconcile states with different identity");
        }
        // A different entity at the root: the old backing is disowned and the new one adopted whole.
        if (isOwned(prev)) {
          owned.remove(prev);
        } else {
          lookupMap(t.fam).remove(prev);
        }
        adoptPB(t, incoming, true);
        return;
      }
    }
    applyAdopt(t, incoming, keyFn, replace);
  }

  private boolean leafSame(Object ov, Object nv) {
    return nv != null && shape.isObject(nv) ? targetsEqual(ov, nv) : same(ov, nv);
  }

  private void applyAdopt(StoreTarget t, Object incoming, Function<Object, Object> keyFn, boolean proj) {
    Object old = t.pb != null ? t.pb : t.v;
    if (same(incoming, old) && !isOwned(old)) {
      return;
    }
    Object prevView = optHooks != null && t.fam != null && t.fam.opt ? optHooks.view(t, old, false) : old;
    boolean nextArr = shape.isArray(incoming);
    adoptPB(t, incoming, false);
    boolean bumped = t.dk == null;
    if (shape.isArray(prevView) != nextArr || isView(old)) {
      notifyFold(t, old, incoming);
      return;
    }
    Map<Object, Node> nodes = t.n;
    int nodesHit = 0;
    if (nextArr) {
      int plen = shape.length(prevView);
      int nlen = shape.length(incoming);
      if (keyFn != null) {
        int i = 0;
        for (int end = Math.min(plen, nlen); i < end; i++) {
          Object nv = shape.get(incoming, i);
          Object pvRaw = shape.get(prevView, i);
          if (!same(pvRaw, nv) && !sameRow(pvRaw, nv, keyFn)) {
            break;
          }
          if (nv != null && shape.isObject(nv) && (!same(pvRaw, nv) || isOwned(nv))) {
            descend(unwrapValue(pvRaw), nv, keyFn, proj, t, i);
          }
          if (!bumped && !leafSame(pvRaw, nv)) {
            bumpDeep(t);
            bumped = true;
          }
          Node node = nodes != null ? nodes.get(i) : null;
          if (node != null) {
            nodesHit++;
            notifyKeyValue(node, shape.get(old, i), nv);
          }
        }
        if (!bumped && i < nlen) {
          bumpDeep(t);
        }
        int structStart = i;
        Map<Object, java.util.ArrayDeque<Integer>> prevByKey = null;
        for (; i < nlen; i++) {
          Object nv = shape.get(incoming, i);
          if (nv != null && shape.isObject(nv)) {
            Object nk = keyFn.apply(nv);
            Object pv = null;
            if (nk != null) {
              if (prevByKey == null) {
                prevByKey = new java.util.HashMap<>();
                for (int j = structStart; j < plen; j++) {
                  Object p = unwrapValue(shape.get(prevView, j));
                  if (p != null && shape.isObject(p)) {
                    Object pk = keyFn.apply(p);
                    if (pk != null) {
                      prevByKey.computeIfAbsent(pk, x -> new java.util.ArrayDeque<>()).add(j);
                    }
                  }
                }
              }
              java.util.ArrayDeque<Integer> m = prevByKey.get(nk);
              if (m != null) {
                pv = unwrapValue(shape.get(prevView, m.poll()));
                if (m.isEmpty()) {
                  prevByKey.remove(nk);
                }
              }
            } else {
              pv = unwrapValue(shape.get(prevView, i));
            }
            descend(pv, nv, keyFn, proj, t, i);
          }
          Node node = nodes != null ? nodes.get(i) : null;
          if (node != null) {
            nodesHit++;
            notifyKeyDiff(node, i, old, incoming);
          }
        }
      } else {
        int dlen = Math.min(plen, nlen);
        for (int i = 0; i < nlen; i++) {
          Object nvP = shape.get(incoming, i);
          Object pvP = shape.get(prevView, i);
          if (i < dlen && nvP != null && shape.isObject(nvP)) {
            descend(unwrapValue(pvP), nvP, keyFn, proj, t, i);
          }
          if (!bumped && !leafSame(pvP, nvP)) {
            bumpDeep(t);
            bumped = true;
          }
          Node node = nodes != null ? nodes.get(i) : null;
          if (node != null) {
            nodesHit++;
            notifyKeyDiff(node, i, old, incoming);
          }
        }
      }
      if (nodes != null && nodesHit < t.nc) {
        for (Object key : new ArrayList<>(nodes.keySet())) {
          if (!(key instanceof Integer && (Integer) key >= 0 && (Integer) key < nlen)) {
            notifyKeyDiff(nodes.get(key), key, old, incoming);
          }
        }
      }
      notifyFoldTail(t, old, incoming);
      return;
    }
    for (Object k : shape.keys(incoming)) {
      Object nv = shape.get(incoming, k);
      Object ov = shape.get(old, k);
      if (optHooks != null && same(ov, nv) && !same(prevView, old)) {
        ov = shape.get(prevView, k);
      }
      boolean isObj = nv != null && shape.isObject(nv);
      Node node = nodes != null ? nodes.get(k) : null;
      if (same(ov, nv) && (!isObj || !isOwned(nv))) {
        if (node != null) {
          nodesHit++;
        }
        continue;
      }
      if (isObj) {
        descend(unwrapValue(shape.get(prevView, k)), nv, keyFn, proj, t, k);
      }
      if (!bumped && !(isObj ? targetsEqual(ov, nv) : same(ov, nv))) {
        bumpDeep(t);
        bumped = true;
      }
      if (node != null) {
        nodesHit++;
        notifyKeyValue(node, ov, nv);
      }
    }
    if (nodes != null && nodesHit < t.nc) {
      for (Object key : new ArrayList<>(nodes.keySet())) {
        if (!shape.has(incoming, key)) {
          notifyKeyDiff(nodes.get(key), key, old, incoming);
        }
      }
    }
    notifyFoldTail(t, old, incoming);
  }

  private void descend(Object pv, Object nv, Function<Object, Object> keyFn, boolean proj, StoreTarget t, Object k) {
    if (pv == null || !shape.isObject(pv)) {
      return;
    }
    StoreFamily fam = t.fam;
    nv = unwrapValue(nv);
    StoreTarget ct = lookupTarget(pv, fam);
    StoreTarget nt = lookupTarget(nv, fam);
    if (ct != null && !sameKey(ct.pk, k)) {
      // A row that is still at its own slot of the committed frame is not this slot's.
      if (same(shape.get(t.v, ct.pk), ct.v)) {
        ct = null;
      } else if (ct.u == t) {
        ct.pk = k;
      }
    }
    if (nt != null && nt != ct) {
      if (same(nt.v, nv)) {
        if (nt.u == t) {
          nt.pk = k;
        }
        return;
      }
      lookupMap(fam).remove(nv);
    }
    if (ct == null || !shape.isWrappable(nv) || shape.isArray(pv) != shape.isArray(nv)) {
      return;
    }
    if (!(k instanceof Integer) && keyFn != null) {
      Object pk = keyFn.apply(pv);
      Object nk = keyFn.apply(nv);
      if (pk != null && nk != null && !sameKey(pk, nk)) {
        return;
      }
    }
    if (!proj && keyFn != null && !ct.d) {
      return;
    }
    applyAdopt(ct, nv, keyFn, proj);
  }

  // ---- projection (store/projection.ts): a derived store

  /** A derive: given its draft, it writes it or returns the value, now, as a CompletionStage or as an AsyncIterable. */
  @FunctionalInterface
  public interface Derive {
    Object apply(Object draft) throws Exception;
  }

  /** Wraps the commit of a derive's answer, as an optimistic store does. */
  @FunctionalInterface
  interface WrapCommit {
    void wrap(Runnable write, Object value);
  }

  private Object wrapDraft(Object inner, java.util.function.BooleanSupplier isActive,
      java.util.function.Consumer<Runnable> aroundWrite, Runnable afterWrite, java.util.function.IntSupplier asking) {
    Scheduler q = s();
    return shape.draftView(inner, new StoreShape.DraftControl() {
      @Override public <T> T read(java.util.function.Supplier<T> op) {
        writeOverride = true;
        try {
          return op.get();
        } finally {
          writeOverride = false;
        }
      }

      @Override public void mutate(Runnable op) {
        if (!isActive.getAsBoolean()) {
          return;
        }
        writeOverride = true;
        int prevQ = q.question;
        if (asking != null) {
          q.question = asking.getAsInt();
        }
        try {
          if (aroundWrite != null) {
            aroundWrite.accept(op);
          } else {
            op.run();
          }
        } finally {
          writeOverride = false;
          q.question = prevQ;
        }
        if (afterWrite != null) {
          afterWrite.run();
        }
      }

      @Override public void alias(Object draft, Object view) {
        StoreTarget t = view != null && shape.isObject(view) ? shape.targetOf(view) : null;
        if (t != null) {
          setLookup(draft, t);
        }
      }
    });
  }

  /** The derive went pending: its leaves' readers wait on the derive itself. */
  void wakeFamily(StoreFamily fam) {
    Node fw = fam.node;
    Errors.NotReadyError error = new Errors.NotReadyError(fw);
    fam.woke = true;
    if (optHooks != null) {
      optHooks.ask(fam);
    }
    fam.eachLiveNode(n -> {
      if (n.subs == null) {
        return;
      }
      rt.async.addPendingSource(n, fw);
      rt.async.propagateStatus(n, STATUS_PENDING, error);
    });
  }

  /** The derive failed: every live reader re-derives and meets the error at its pull. */
  void errorFamily(StoreFamily fam, Throwable error) {
    fam.eachLiveNode(n -> {
      for (Link l = n.subs; l != null; l = l.nextSub) {
        Node sub = l.sub;
        if (!((sub.statusFlags & STATUS_ERROR) != 0 && sub.x != null && sub.x.error == error)) {
          s().dirtyQueue.enqueueSub(sub);
        }
      }
    });
    s().schedule();
  }

  void settleFamily(StoreFamily fam) {
    if (!fam.woke) {
      return;
    }
    fam.woke = false;
    Node fw = fam.node;
    fam.eachLiveNode(n -> {
      if (n.subs != null) {
        rt.async.settlePendingSource(n, fw);
      }
    });
    s().insertSubs(fw);
  }

  private void familyStatus(StoreFamily fam, int status, Throwable error) {
    if ((status & STATUS_PENDING) != 0) {
      wakeFamily(fam);
    } else if (status == 0) {
      settleFamily(fam);
    } else if ((status & STATUS_ERROR) != 0) {
      errorFamily(fam, error);
    }
  }

  /** One pass of a derive, inside its computed: the draft's writes and the answer's reconcile. */
  void runProjectionComputed(Object wrappedStore, Derive fn, Function<Object, Object> key, WrapCommit wrapCommit,
      java.util.function.Consumer<Runnable> aroundDraftWrite) {
    Scheduler q = s();
    Node owner = (Node) rt.owners.getOwner();
    StoreTarget target = shape.targetOf(wrappedStore);
    StoreFamily fam = target.fam;
    int run = ++fam.run;
    if (q.observeFlightHook != null && (fam.woke || fam.w != 0)) {
      fam.w = 0;
      owner.config &= ~CONFIG_VERDICT;
    }
    java.util.function.IntSupplier asking = () ->
      q.question != 0 ? q.question : owner.x != null && owner.x.inFlight != null ? owner.x.q : 0;
    Object draft = wrapDraft(wrappedStore, () -> fam.run == run && !rt.owners.isDisposed(owner), aroundDraftWrite, () -> {
      if ((owner.statusFlags & STATUS_PENDING) == 0 && !owner.loading) {
        q.schedule();
      }
    }, asking);
    boolean first = run == 1;
    Runnable pass = () -> storeSetter(draft, st -> {
      Object result;
      try {
        result = fn.apply(st);
      } catch (Exception e) {
        throw Errors.<RuntimeException>sneak(e);
      }
      java.util.function.BiConsumer<Object, Boolean> commit = (v, wake) -> {
        if (v != null && !same(v, st)) {
          Runnable write = () -> {
            int prevQ = q.question;
            q.question = asking.getAsInt();
            try {
              storeSetter(wrappedStore, s2 -> {
                reconcileState(v, s2, key, true);
                return null;
              }, false);
            } finally {
              q.question = prevQ;
            }
          };
          if (wrapCommit != null) {
            wrapCommit.wrap(write, v);
          } else {
            write.run();
          }
        }
        if (!first || (owner.statusFlags & STATUS_UNINITIALIZED) == 0) {
          settleFamily(fam);
        }
        if (wake) {
          q.insertSubs(owner);
        }
      };
      Object sync;
      if (Async.isAsync(result)) {
        sync = rt.async.handleAsync(owner, result, v -> commit.accept(v, true), (error, pending) -> {
          if (first && (owner.statusFlags & STATUS_UNINITIALIZED) != 0) {
            return;
          }
          if (pending) {
            wakeFamily(fam);
          } else {
            errorFamily(fam, error);
          }
        });
      } else {
        // handleAsync's own answer to a plain value, which the port's recompute has inline.
        if (owner.x != null) {
          owner.x.inFlight = null;
          if (q.holdWorkHook != null) {
            owner.config &= ~CONFIG_ASYNC;
          }
        }
        owner.loading = false;
        sync = result;
      }
      // (The status is the one before this pass: recompute sets it after.)
      if (!owner.loading) {
        commit.accept(sync, (owner.statusFlags & (STATUS_PENDING | STATUS_ERROR)) != 0);
      }
      return null;
    }, false);
    try {
      if (first) {
        runDirect(pass);
      } else {
        pass.run();
      }
    } catch (Throwable e) {
      if (!first) {
        if (e instanceof Errors.NotReadyError) {
          wakeFamily(fam);
        } else {
          errorFamily(fam, e);
        }
      }
      throw Errors.<RuntimeException>sneak(e);
    }
    if ((owner.statusFlags & STATUS_PENDING) != 0 && owner.x != null && owner.x.inFlight != null && !owner.loading && !first) {
      wakeFamily(fam);
    }
  }

  /** A derived store's family and root: Solid's createProjectionInternal. fn is null for a plain optimistic store. */
  Object createProjection(Derive fn, Object seed, Function<Object, Object> key, boolean opt) {
    shape();
    StoreFamily fam = new StoreFamily();
    fam.opt = opt;
    fam.key = key;
    fam.st = (status, error) -> familyStatus(fam, status, error);
    Object store = wrap(seed, null, null, fam);
    if (fn != null) {
      Node node = rt.core.computed(prev -> {
        if (fam.node == null) {
          fam.node = (Node) rt.owners.getOwner();
        }
        runProjectionComputed(store, fn, key, null, null);
        return null;
      }, null);
      node.config &= ~CONFIG_AUTO_DISPOSE;
      fam.node = node;
      node.fam = fam;
    }
    return store;
  }

  /**
   * Solid's createOptimisticStore: a store whose setter's writes are guesses, shown at once and undone when
   * the action that made them ends. With a derive it is a projection too, and a landing confirms a guess.
   */
  Object createOptimisticStore(Derive fn, Object seed, Function<Object, Object> key) {
    shape();
    if (optHooks == null) {
      optHooks = new StoreOptimistic(rt, this);
    }
    return createProjection(fn, seed, key, true);
  }

  /** Solid's affects(store) and affects(store, key): the record, or one key of it, is about to change. */
  void affects(Object view, Object key, boolean keyed) {
    StoreTarget t = shape.targetOf(view);
    if (affectsHooks == null) {
      affectsHooks = new StoreAffects(rt, this);
    }
    rt.marks.affects(((StoreAffects) affectsHooks).storeMarks(t, key, keyed));
  }

  /** The derive of a derived store's view, Solid's store[$REFRESH]. */
  Node deriveOf(Object view) {
    StoreTarget t = shape.targetOf(view);
    return t != null && t.fam != null ? t.fam.node : null;
  }

  // ---- snapshot and deep

  /** Tracking snapshot: subscribes to every reachable record. */
  Object deep(Object value) {
    StoreTarget t0 = value != null && shape.isObject(value) ? shape.targetOf(value) : null;
    if (t0 == null || !same(t0.px, value)) {
      return value;
    }
    walkDeep(t0, shape.identityMap());
    return snapshotWalk(value, shape.identityMap(), t0.fam);
  }

  private void walkDeep(StoreTarget t, Map<Object, Boolean> visited) {
    if (t.fam != null) {
      pullFamily(t);
    }
    Object src = enumerationSource(t);
    if (visited.put(src, Boolean.TRUE) != null) {
      return;
    }
    rt.core.read(getDeepNode(t));
    // A chained backing changes without this store's nodes moving: witness the inner store too.
    for (StoreTarget it = t; it.ch;) {
      it = shape.targetOf(it.v);
      rt.core.read(getContainerNode(it));
      rt.core.read(getDeepNode(it));
    }
    for (Object key : shape.keys(src)) {
      Object child = shape.get(src, key);
      if (child == null || !shape.isObject(child)) {
        continue;
      }
      if (t.ch && shape.targetOf(child) == null) {
        child = resolveChainedRaw(t, key, child);
      }
      StoreTarget ct = lookupTarget(child, t.fam);
      if (ct == null) {
        if (!shape.isWrappable(child)) {
          continue;
        }
        wrap(child, t, key);
        ct = lookupTarget(child, t.fam);
        if (ct == null) {
          ct = shape.targetOf(child);
        }
      }
      if (ct != null) {
        walkDeep(ct, visited);
      }
    }
  }

  /** Non-tracking snapshot of a store's current value: the source object for what was not written. */
  Object snapshot(Object value) {
    StoreTarget t = value != null && shape.isObject(value) ? shape.targetOf(value) : null;
    return snapshotWalk(value, shape.identityMap(), t != null ? t.fam : null);
  }

  private Object snapshotBacking(StoreTarget t) {
    Object backing = t.v;
    Object pb = t.pb;
    if (pb != null) {
      Node k = t.k;
      if (inDraft(t) || writeOverride || k == null || (k.config & CONFIG_HELD) == 0) {
        backing = pb;
      } else if (stagingReader() != null) {
        backing = asBacking(rt.core.untrack(() -> rt.core.read(k)));
      }
    }
    return optHooks != null && optRead(t) ? optHooks.view(t, backing, false) : backing;
  }

  private Object snapshotWalk(Object value, Map<Object, Object> seen, StoreFamily fam) {
    if (value == null || !shape.isObject(value)) {
      return value;
    }
    Object src = value;
    for (boolean entry = true;; entry = false) {
      StoreTarget viaView = shape.targetOf(src);
      boolean viaProxy = viaView != null && viaView.v != null;
      StoreTarget t = viaProxy ? viaView : null;
      if (t == null && fam != null) {
        t = lookupTarget(src, fam);
      }
      if (t == null) {
        t = lookupTarget(src, null);
      }
      if (t == null) {
        break;
      }
      if (entry && !viaProxy && fam != null && t.fam != fam) {
        StoreTarget outer = deref(lookupMap(fam).get(t.px));
        if (outer != null) {
          t = outer;
        }
      }
      if (t.fam != null) {
        fam = t.fam;
      }
      Object backing = snapshotBacking(t);
      if (same(backing, src)) {
        break;
      }
      src = backing;
    }
    if (!shape.isWrappable(src)) {
      return src;
    }
    Object cached = seen.get(src);
    return cached != null ? cached : snapshotRecord(src, seen, fam);
  }

  private Object snapshotRecord(Object src, Map<Object, Object> seen, StoreFamily fam) {
    if (isOwned(src)) {
      Object copy = shape.clone(src);
      seen.put(src, copy);
      for (Object key : shape.keys(src)) {
        Object cv = shape.get(src, key);
        if (cv != null && shape.isObject(cv)) {
          Object walked = snapshotWalk(cv, seen, fam);
          if (!same(walked, cv)) {
            shape.set(copy, key, walked);
          }
        }
      }
      // An immutable kind of object is built once its copy is filled.
      Object sealed = shape.seal(copy);
      seen.put(src, sealed);
      return sealed;
    }
    seen.put(src, src);
    Object copy = null;
    for (Object key : shape.keys(src)) {
      Object cv = shape.get(src, key);
      if (cv == null || !shape.isObject(cv)) {
        continue;
      }
      Object walked = snapshotWalk(cv, seen, fam);
      if (!same(walked, cv)) {
        if (copy == null) {
          copy = shape.clone(src);
          seen.put(src, copy);
        }
        shape.set(copy, key, walked);
      }
    }
    if (copy == null) {
      return src;
    }
    Object sealed = shape.seal(copy);
    seen.put(src, sealed);
    return sealed;
  }
}
