package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import j2act.internal.signals.StoreTarget.StoreFamily;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Port of @solidjs/signals store/optimistic.ts at 9449845: optimistic stores on lanes (S2: a guess is a patch). A
 * user write to an optimistic store is one guess per target: the user's draft, carried by the target's guess carrier
 * (a slot node keyed {@link Stores#PATCH}), with the keys the draft changed recorded on it. Lanes do the rest on that
 * one node: the guess shows under the action's hold, a landing beneath it stages as truth and confirms it when
 * equal, and the action's settle dissolves it. The committed backing is never touched by a guess. Reads find the
 * carrier by key: a key the guess changed reads through it, tracked; presence, descriptors and enumeration are the
 * carrier frame's.
 */
final class StoreOptimistic implements Stores.OptHooks {
  /** Solid's `g` left undefined: not an arrangement guess. */
  private static final Object UNSET = new Object();

  /** What Solid keeps on a carrier node: the keys the guess changed and, for a keyed list, its rows and removed keys. */
  private static final class Patch {
    Set<Object> wk;
    Set<Object> del;
    Set<Object> removed;
    /** The keys this guess's frame only carries over from another live guess's carrier (#4784). */
    Set<Object> borrowed;
    /** The rows as written (a List), null for the #4525 re-base, UNSET for no arrangement guess. */
    Object g = UNSET;
  }

  private final Runtime rt;
  private final Stores stores;
  /** A target's guess carriers, one per lane, oldest first. */
  private final Map<StoreTarget, List<Node>> carriers = new WeakHashMap<>();
  private final Map<Node, Patch> patches = new WeakHashMap<>();
  /** An optimistic setter's targets whose staging was set aside, restored when its writes become guesses. */
  private final Map<StoreTarget, Object> optStaged = new HashMap<>();
  private final Set<StoreFamily> overlaidFamilies = new LinkedHashSet<>();
  private Object heldC;
  private long heldAt = -1;
  private int heldV;

  StoreOptimistic(Runtime rt, Stores stores) {
    this.rt = rt;
    this.stores = stores;
    Scheduler q = rt.scheduler;
    q.echoesHook = (a, b) -> {
      try {
        return echoes(a, b);
      } catch (RuntimeException | StackOverflowError e) {
        // A row that refers to itself has no end to compare to: not an echo.
        return false;
      }
    };
    q.slotTakeHook = this::slotTake;
    q.laneRebaseHook = this::laneRebase;
    q.slotDirtyHook = (n, l) -> readersOf(n, node -> q.laneDirty(node, l));
    q.slotRerunHook = (n, t) -> readersOf(n, node -> {
      for (Link s = node.subs; s != null; s = s.nextSub) {
        q.staleReader(s.sub, t);
      }
    });
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  private StoreShape shape() {
    return stores.shape();
  }

  private List<Node> kgOf(StoreTarget t) {
    return carriers.get(t);
  }

  private Patch patch(Node g) {
    return patches.get(g);
  }

  /** A key Solid's INDEX regex or "length" names: an array slot of a keyed list's arrangement. */
  private static boolean arrangementKey(Object key) {
    return key == StoreShape.LENGTH || key instanceof Integer;
  }

  /**
   * The newest live guess carrier for key: a lane's guess, or one a correction re-homed into its hold. Its record has
   * the key, or, for a read, the key is an index or the length of a keyed list's arrangement guess. A landing asks
   * without reads: a key the guess only carried over is the truth's.
   */
  private Node guessFor(StoreTarget t, Object key, boolean shape, boolean reads) {
    List<Node> kg = kgOf(t);
    if (kg != null) {
      for (int i = kg.size() - 1; i >= 0; i--) {
        Node g = kg.get(i);
        Patch p = patch(g);
        if ((g.config & (CONFIG_OVERRIDE | CONFIG_HELD)) != 0
          && ((p.wk != null && p.wk.contains(key)) || (p.del != null && p.del.contains(key))
            || (reads && p.g != UNSET && !(p.borrowed != null && p.borrowed.contains(key)) && arrangementKey(key)))) {
          return g;
        }
      }
    }
    return null;
  }

  /** The newest live guess carrier: the frame a structural read sees. */
  private Node newestGuess(StoreTarget t) {
    List<Node> kg = kgOf(t);
    if (kg != null) {
      for (int i = kg.size() - 1; i >= 0; i--) {
        if ((kg.get(i).config & (CONFIG_OVERRIDE | CONFIG_HELD)) != 0) {
          return kg.get(i);
        }
      }
    }
    return null;
  }

  /** The hold's carrier of a truth staged beneath the screen: the container node while a transaction holds its staging. */
  private static Node heldCarrier(StoreTarget t) {
    Node k = t.k;
    return k != null && (k.config & CONFIG_HELD) != 0 && k.pendingValue != NOT_PENDING ? k : null;
  }

  /** A guessed key's value from the carrier's frame. */
  private Object carrierValue(Object frame, Object key) {
    StoreShape shape = shape();
    return key == StoreShape.LENGTH && shape.isArray(frame) ? (Object) shape.length(frame) : shape.get(frame, key);
  }

  private boolean frameHas(Object frame, Object key) {
    StoreShape shape = shape();
    return key == StoreShape.LENGTH ? shape.isArray(frame) : shape.has(frame, key);
  }

  private boolean isObject(Object v) {
    return v != null && shape().isObject(v);
  }

  /**
   * The view the setter composes on (writer): the committed frame, a chained backing resolved through the inner
   * store's, with the lane's value and the tick's own guess over it; plain: the committed frame alone.
   */
  private Object writerBacking(StoreTarget t, boolean plain) {
    Object base = stores.committed(t);
    StoreTarget inner = isObject(base) ? shape().targetOf(base) : null;
    Object src = inner == null ? base : writerBacking(inner, plain);
    return plain ? src : view(t, src, true);
  }

  /** src with the target's guess over it: the carrier's unflushed guess or lane value when it carries one. */
  @Override public Object view(StoreTarget t, Object src, boolean writer) {
    List<Node> kg = kgOf(t);
    if (kg != null) {
      for (int i = kg.size() - 1; i >= 0; i--) {
        Node g = kg.get(i);
        if (writer) {
          // The writer composes on the screen with the tick's own guesses (D-34, D-37).
          if ((g.config & CONFIG_OVERRIDE) != 0 || rt.lanes.pendingGuessOf(g) != NOT_PENDING) {
            Object v = rt.lanes.guessedValueOf(g);
            if (v != NOT_PENDING) {
              return v;
            }
          }
          if ((g.config & CONFIG_HELD) != 0) {
            return g.value;
          }
        } else if ((g.config & (CONFIG_OVERRIDE | CONFIG_HELD)) != 0) {
          // A snapshot, deep(), a reconcile's key matching: the carrier as core reads it for this reader.
          Object v = rt.core.untrack(() -> rt.core.read(g));
          if (v != NOT_PENDING) {
            return v;
          }
        }
      }
    }
    return src;
  }

  /** The user's draft: a clone of the view the user saw (#3665); a staging already on the target is set aside. */
  @Override public Object draft(StoreTarget t) {
    if (t.pb != null) {
      optStaged.put(t, t.pb);
    }
    return stores.cloneRaw(writerBacking(t, false), t);
  }

  /**
   * The target's guess carrier, made at the first guess: its committed value the frame the guess is shown over. A
   * guess goes on one with a guess still unflushed, or a live one whose record shares a key; else a new one.
   */
  private Node guessCarrier(StoreTarget t, Set<Object> keys) {
    List<Node> kg = carriers.computeIfAbsent(t, k -> new ArrayList<>());
    for (int i = kg.size() - 1; i >= 0; i--) {
      Node g = kg.get(i);
      boolean live = (g.config & (CONFIG_OVERRIDE | CONFIG_GUESS)) != 0;
      if (!live && rt.lanes.pendingGuessOf(g) == NOT_PENDING) {
        continue;
      }
      if (rt.lanes.pendingGuessOf(g) != NOT_PENDING || keys == null) {
        return entangle(t, g, keys);
      }
      Patch p = patch(g);
      if (p.wk != null) {
        for (Object key : keys) {
          if (p.wk.contains(key) || (p.del != null && p.del.contains(key))) {
            return g;
          }
        }
      }
    }
    Node[] self = new Node[1];
    Node g = rt.core.slotSignal(stores.committed(t), (a, b) -> patchEquals(self[0], a, b), t, Stores.PATCH, false);
    self[0] = g;
    // Written over a held truth: that truth is the one beneath the guess, judged at the body's end (A18, #3330).
    Node p = heldCarrier(t);
    if (p != null) {
      g.pendingValue = p.pendingValue;
      s().holdNode(g, s().txOf(p));
    }
    patches.put(g, new Patch());
    kg.add(g);
    return g;
  }

  /** This flush's carrier writes a key another action's live guess holds: their frames entangle (A34 (1)). */
  private Node entangle(StoreTarget t, Node g, Set<Object> keys) {
    Scheduler q = s();
    if (keys != null) {
      outer:
      for (Node o : kgOf(t)) {
        Patch op = patch(o);
        if (o != g && (o.config & CONFIG_OVERRIDE) != 0 && op.wk != null) {
          for (Object key : keys) {
            if (op.wk.contains(key) || (op.del != null && op.del.contains(key))) {
              Transaction holder = Scheduler.resolveTx(q.txOf(o).parent);
              if ((g.config & CONFIG_OVERRIDE) != 0) {
                q.merge(Scheduler.resolveTx(q.txOf(g).parent), holder);
              } else if ((g.config & CONFIG_HELD) == 0) {
                q.holdNode(g, holder);
              }
              break outer;
            }
          }
        }
      }
    }
    return g;
  }

  /** The derive's open question is a re-ask of the same one (D-34): its own refresh, or every source it waits on re-asked. */
  private static boolean reasked(Node d) {
    if ((d.flags & REACTIVE_REASK) != 0 || (d.x != null && d.x.reask)) {
      return true;
    }
    Set<Node> ps = d.x != null ? d.x.pendingSources : null;
    if (ps == null || ps.isEmpty()) {
      return false;
    }
    for (Node s : ps) {
      if (s == d || !((s.flags & REACTIVE_REASK) != 0 || (s.x != null && s.x.reask))) {
        return false;
      }
    }
    return true;
  }

  /** The carrier's comparator: a truth judged against the guess over the keys the guess changed. */
  private boolean patchEquals(Node self, Object a, Object b) {
    if (stores.same(a, b)) {
      return true;
    }
    if (!isObject(a) || !isObject(b)) {
      return false;
    }
    StoreShape shape = shape();
    // A chained backing is judged as the inner store's committed frame.
    StoreTarget at = shape.targetOf(a);
    if (at != null) {
      a = writerBacking(at, true);
    }
    StoreTarget bt = shape.targetOf(b);
    if (bt != null) {
      b = writerBacking(bt, true);
    }
    StoreFamily fam = ((StoreTarget) self.host).fam;
    Patch p = patch(self);
    if (p.g != UNSET && !arrangementEqual(a, b, fam)) {
      return false;
    }
    if (p.wk != null) {
      for (Object key : p.wk) {
        if (frameHas(a, key) != frameHas(b, key)) {
          return false;
        }
        if (!s().echoesHook.test(carrierValue(a, key), carrierValue(b, key))) {
          return false;
        }
      }
    }
    if (p.del != null) {
      for (Object key : p.del) {
        if (frameHas(a, key) != frameHas(b, key)) {
          return false;
        }
      }
    }
    return true;
  }

  /** A user setter's writes on an optimistic family, as one guess on the target's carrier. */
  @Override public Object writes(StoreTarget t, Object pb) {
    StoreShape shape = shape();
    // The view the user saw: the committed frame with the lane's value and the tick's own guess over it.
    Object old = writerBacking(t, false);
    boolean isArr = shape.isArray(pb);
    Set<Object> changed = new LinkedHashSet<>();
    Set<Object> del = null;
    boolean structural = false;
    for (Object key : shape.keys(pb)) {
      Object nv = stores.unwrapValue(shape.get(pb, key));
      if (!shape.has(old, key)) {
        changed.add(key);
        structural = true;
      } else {
        Object previous = shape.get(old, key);
        Object ov = stores.unwrapValue(previous);
        // A row the setter carried over is not a write (#4724): the draft's rows are clones of the view's.
        if (!stores.same(ov, nv) && !stores.targetsEqual(ov, nv)) {
          if (isArr && echoes(ov, nv)) {
            // The frame carries the row the view showed: the same object wraps as the same proxy.
            shape.set(pb, key, previous);
          } else {
            changed.add(key);
            if (isArr) {
              structural = true;
            }
          }
        }
      }
    }
    if (isArr && shape.isArray(old) && shape.length(old) != shape.length(pb)) {
      changed.add(StoreShape.LENGTH);
    }
    for (Object key : shape.keys(old)) {
      if (shape.has(pb, key)) {
        continue;
      }
      if (del == null) {
        del = new LinkedHashSet<>();
      }
      del.add(key);
      structural = true;
    }
    Object staged = optStaged.remove(t);
    if (changed.isEmpty() && del == null) {
      return staged;
    }
    Set<Object> keys = changed;
    if (del != null) {
      keys = new LinkedHashSet<>(changed);
      keys.addAll(del);
    }
    Node g = guessCarrier(t, keys);
    Patch p = patch(g);
    // The record accumulates across a lane's writes to the same target.
    if (p.wk == null) {
      p.wk = changed;
    } else {
      p.wk.addAll(changed);
    }
    if (del != null) {
      if (p.del == null) {
        p.del = del;
      } else {
        p.del.addAll(del);
      }
    }
    // The keys this guess's frame only carries over from another live guess's carrier (#4784): the arrangement read
    // falls through on those keys, to the lender's carrier while it lives, to the truth once it dissolves. A row the
    // setter carried over verbatim is not borrowed (#4724).
    List<Node> kg = kgOf(t);
    if (kg != null && kg.size() > 1) {
      for (Node o : kg) {
        if (o == g) {
          continue;
        }
        boolean live = (o.config & (CONFIG_OVERRIDE | CONFIG_GUESS | CONFIG_HELD)) != 0
          || rt.lanes.pendingGuessOf(o) != NOT_PENDING;
        if (!live) {
          continue;
        }
        Patch op = patch(o);
        List<Object> lent = new ArrayList<>();
        if (op.wk != null) {
          lent.addAll(op.wk);
        }
        if (op.del != null) {
          lent.addAll(op.del);
        }
        for (Object key : lent) {
          if (!changed.contains(key) && !(del != null && del.contains(key)) && !carriedVerbatim(t, old, pb, key)) {
            if (p.borrowed == null) {
              p.borrowed = new HashSet<>();
            }
            p.borrowed.add(key);
          }
        }
      }
    }
    if (structural && isArr) {
      List<Object> rows = rowsOf(pb);
      // The keys of the view the setter saw that are not the guess's own rows, which a re-base takes from the new
      // truth: rows the setter removed, and rows it only carried over from the truth beneath.
      Set<Object> removed = null;
      Function<Object, Object> keyFn = t.fam.key;
      if (keyFn != null) {
        removed = new HashSet<>();
        Set<Object> kept = new HashSet<>();
        Object base = stores.committed(t);
        Map<Object, Boolean> truth = shape.identityMap();
        for (Object r : rowsOf(base)) {
          if (isObject(r)) {
            truth.put(r, Boolean.TRUE);
          }
        }
        for (Object r : rows) {
          Object k = shape.isWrappable(r) ? keyFn.apply(r) : null;
          if (k != null) {
            kept.add(k);
          }
        }
        // The view's rows, then the truth's: a truth row the view lacked was removed by another action's guess (F-11).
        List<Object> all = new ArrayList<>(rowsOf(old));
        int n = all.size();
        all.addAll(rowsOf(base));
        for (int i = 0; i < all.size(); i++) {
          Object raw = all.get(i);
          Object k = shape.isWrappable(raw) ? keyFn.apply(raw) : null;
          if (k != null && (!kept.contains(k) || (i < n && truth.containsKey(raw)))) {
            removed.add(k);
          }
        }
      }
      p.removed = removed;
      p.g = rows;
      // Written while the derive asks a new question (not a refresh): the new answer re-bases it (#4525, #4656).
      Node d = t.fam.node;
      if (removed != null && d != null && (d.statusFlags & STATUS_PENDING) != 0 && !reasked(d)) {
        p.g = null;
      }
    }
    // By value: the draft is the guess as written.
    rt.lanes.optimisticWrite(g, pb, null);
    if (t.dk != null) {
      rt.lanes.optimisticWrite(t.dk, new Object(), null);
    }
    markOverlaid(t);
    return staged;
  }

  /**
   * A keyed arrangement guess carries another guess's row verbatim when the setter returned the view's row unchanged:
   * the row target decides the row's contents (#4724). Primitives always borrow (#4784).
   */
  private boolean carriedVerbatim(StoreTarget t, Object old, Object pb, Object key) {
    if (!(key instanceof Integer) || t.fam == null || t.fam.key == null) {
      return false;
    }
    Object row = stores.unwrapValue(shape().get(pb, key));
    // The view of a chained store can hold the inner store's wrapped rows where Solid's holds raws: compared unwrapped.
    return shape().isWrappable(row) && stores.same(row, stores.unwrapValue(shape().get(old, key)));
  }

  /** An array's rows, unwrapped. */
  private List<Object> rowsOf(Object array) {
    StoreShape shape = shape();
    int n = shape.length(array);
    List<Object> rows = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      rows.add(stores.unwrapValue(shape.get(array, i)));
    }
    return rows;
  }

  /** An array of the store's shape holding rows, built over like (an array). */
  private Object arrayOf(List<Object> rows, Object like) {
    StoreShape shape = shape();
    Object out = shape.clone(like);
    shape.set(out, StoreShape.LENGTH, rows.size());
    for (int i = 0; i < rows.size(); i++) {
      shape.set(out, i, rows.get(i));
    }
    return out;
  }

  /** Two arrangements (a guess's rows or keys against a truth's) by row identity, by key when the family has one. */
  private boolean arrangementEqual(Object a, Object b, StoreFamily fam) {
    StoreShape shape = shape();
    if (shape.isArray(a) != shape.isArray(b)) {
      return false;
    }
    if (shape.isArray(a)) {
      int n = shape.length(a);
      if (n != shape.length(b)) {
        return false;
      }
      for (int i = 0; i < n; i++) {
        if (!rowSame(shape.get(a, i), shape.get(b, i), fam)) {
          return false;
        }
      }
      return true;
    }
    List<Object> ak = shape.keys(a);
    if (ak.size() != shape.keys(b).size()) {
      return false;
    }
    for (Object k : ak) {
      if (!shape.has(b, k)) {
        return false;
      }
    }
    return true;
  }

  /** Two rows are the same row: identical, one target, or one key. */
  private boolean rowSame(Object a, Object b, StoreFamily fam) {
    if (stores.same(a, b) || stores.targetsEqual(a, b)) {
      return true;
    }
    Function<Object, Object> keyFn = fam.key;
    StoreShape shape = shape();
    if (keyFn == null || !shape.isWrappable(a) || !shape.isWrappable(b)) {
      return false;
    }
    a = stores.unwrapValue(a);
    b = stores.unwrapValue(b);
    StoreTarget ta = stores.lookupTarget(a, fam);
    StoreTarget tb = stores.lookupTarget(b, fam);
    if (ta != null || tb != null) {
      return ta == tb;
    }
    return Stores.sameKey(keyFn.apply(a), keyFn.apply(b));
  }

  /** The truth is the guess's echo when reconcile would write nothing into it. */
  private boolean echoes(Object a, Object b) {
    if (stores.same(a, b)) {
      return true;
    }
    StoreShape shape = shape();
    if (!shape.isWrappable(a) || !shape.isWrappable(b)) {
      return false;
    }
    a = stores.unwrapValue(a);
    b = stores.unwrapValue(b);
    List<Object> ak = shape.keys(a);
    if (shape.isArray(a) != shape.isArray(b) || ak.size() != shape.keys(b).size()) {
      return false;
    }
    for (Object k : ak) {
      if (!shape.has(b, k) || !echoes(shape.get(a, k), shape.get(b, k))) {
        return false;
      }
    }
    return true;
  }

  /** A user's reconcile on an optimistic family: the keyed diff is written into the draft. */
  @Override public void reconcile(Object draft, Object incoming, Function<Object, Object> keyFn) {
    boolean was = diffing;
    diffing = true;
    try {
      reconcileDraft(draft, incoming, keyFn);
    } finally {
      diffing = was;
    }
  }

  /** In the keyed diff a returned array is written by: the user's own reads are the setter's, not these (#4788). */
  private boolean diffing;

  /**
   * #4788 (D-33): an action read key of t (v, else the writer's view of it): when a live guess wrote that key, a guess
   * the action writes next derives from it. A row reached through a guessed index is navigation, not a value.
   */
  private void noteRead(StoreTarget t, Object key, Object v, boolean hasV) {
    Scheduler q = s();
    Node g = q.actionDepth > 0 && !diffing ? guessFor(t, key, false, false) : null;
    if (g != null && (g.config & CONFIG_OVERRIDE) != 0
      && !shape().isWrappable(hasV && v != null && v != shape().undefined() ? v
        : carrierValue(rt.lanes.guessedValueOf(g), key))) {
      rt.lanes.readGuesses.add(q.txOf(g));
      rt.lanes.readGuesses.add(q.question);
    }
  }

  @Override public void drafted(StoreTarget t, Object key) {
    noteRead(t, key, null, false);
  }

  private void reconcileDraft(Object draft, Object incoming, Function<Object, Object> keyFn) {
    StoreShape shape = shape();
    StoreTarget dt = shape.targetOf(draft);
    if (shape.isArray(incoming)) {
      int n = stores.length(dt);
      List<Object> prev = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        prev.add(stores.get(dt, i));
      }
      Map<Object, Object> byKey = null;
      if (keyFn != null) {
        byKey = new HashMap<>();
        for (int i = 0; i < n; i++) {
          Object raw = stores.unwrapValue(prev.get(i));
          if (!shape.isWrappable(raw)) {
            continue;
          }
          Object k = keyFn.apply(raw);
          if (k != null && !byKey.containsKey(k)) {
            byKey.put(k, prev.get(i));
          }
        }
      }
      int len = shape.length(incoming);
      for (int i = 0; i < len; i++) {
        Object nv = shape.get(incoming, i);
        Object match = null;
        if (shape.isWrappable(nv)) {
          if (byKey != null) {
            Object k = keyFn.apply(nv);
            if (k != null) {
              match = byKey.get(k);
            }
          } else if (i < n && shape.isWrappable(stores.unwrapValue(prev.get(i)))) {
            match = prev.get(i);
          }
        }
        if (match != null) {
          if (!stores.same(stores.unwrapValue(match), nv)) {
            reconcileDraft(match, nv, keyFn);
          }
          if (!stores.same(stores.get(dt, i), match)) {
            stores.set(dt, i, match);
          }
        } else if (!stores.same(stores.unwrapValue(stores.get(dt, i)), nv)) {
          stores.set(dt, i, nv);
        }
      }
      if (stores.length(dt) != len) {
        stores.set(dt, StoreShape.LENGTH, len);
      }
      return;
    }
    for (Object k : shape.keys(incoming)) {
      Object nv = shape.get(incoming, k);
      Object cur = stores.get(dt, k);
      Object curRaw = stores.unwrapValue(cur);
      if (shape.isWrappable(nv) && shape.isWrappable(curRaw) && shape.isArray(nv) == shape.isArray(curRaw)
        && (keyFn == null || shape.isArray(nv) || Stores.sameKey(keyFn.apply(nv), keyFn.apply(curRaw)))) {
        if (!stores.same(curRaw, nv)) {
          reconcileDraft(cur, nv, keyFn);
        }
      } else if (!stores.same(curRaw, nv)) {
        stores.set(dt, k, nv);
      }
    }
    for (Object k : new ArrayList<>(stores.ownKeys(dt))) {
      if (!shape.has(incoming, k)) {
        stores.delete(dt, k);
      }
    }
  }

  /** The nodes the readers of a guess carrier's keys link: the leaves and presence nodes of the keys it changed, and the container. */
  private void readersOf(Node n, Consumer<Node> fn) {
    Patch p = n.key == Stores.PATCH ? patch(n) : null;
    if (p == null) {
      return;
    }
    StoreTarget t = (StoreTarget) n.host;
    List<Object> keys = new ArrayList<>();
    if (p.wk != null) {
      keys.addAll(p.wk);
    }
    if (p.del != null) {
      keys.addAll(p.del);
    }
    for (Object key : keys) {
      Node leaf = t.n != null ? t.n.get(key) : null;
      if (leaf != null && leaf.subs != null) {
        fn.accept(leaf);
      }
      Node has = t.h != null ? t.h.get(key) : null;
      if (has != null && has.subs != null) {
        fn.accept(has);
      }
    }
    Node k = t.k;
    if (k != null && k.subs != null) {
      fn.accept(k);
    }
  }

  /**
   * Solid's GlobalQueue._slotTake: the family's derive, re-asking in a lane of another action than the one that
   * guessed this target, takes the target's guess whole; back, the guess comes back on the target's carrier.
   */
  private Object slotTake(Node n, boolean back) {
    StoreTarget t = n.host instanceof StoreTarget ? (StoreTarget) n.host : null;
    if (back) {
      markOverlaid(t);
      List<Node> kg = carriers.computeIfAbsent(t, k -> new ArrayList<>());
      if (!kg.contains(n)) {
        kg.add(n);
      }
      return n;
    }
    Scheduler q = s();
    Node derive = t != null && t.fam != null ? t.fam.node : null;
    Transaction d = derive != null && derive.x != null ? derive.x.transaction : null;
    Transaction g = q.txOf(n);
    if (d == null || !d.lane || sameAction(d, g)) {
      return null;
    }
    List<Node> all = new ArrayList<>(kgOf(t) != null ? kgOf(t) : Collections.emptyList());
    all.add(t.dk);
    for (Node m : all) {
      if (m != null && (m.config & CONFIG_GUESS) != 0 && q.txOf(m) == g) {
        rt.lanes.take(m, d, m != n);
      }
    }
    return Boolean.TRUE;
  }

  /**
   * Solid's GlobalQueue._laneRebase (Q-D): an older question's truth landed beneath a guess. The guess's edit is
   * re-based over it: a keyed list's order from the guess, rows from the truth by key, rows the guess added kept,
   * truth rows the guess lacks appended unless it removed them. A current truth folds beneath a shown keyed
   * arrangement guess (D-34), else is held beneath the guess. True: the lane holds the landing.
   */
  @SuppressWarnings("unchecked")
  private boolean laneRebase(Node el, Object truth, boolean older) {
    Scheduler q = s();
    StoreShape shape = shape();
    StoreTarget t = el.host instanceof StoreTarget ? (StoreTarget) el.host : null;
    // Not a store's: the lane judges the write.
    if (t == null) {
      return false;
    }
    if (el.key != Stores.PATCH) {
      // The deep witness's guess: a bump beneath a shown keyed arrangement guess folds that lane, else the lane judges it.
      if (older) {
        return true;
      }
      List<Node> kg = kgOf(t);
      if (kg != null) {
        for (Node c : kg) {
          if ((c.config & CONFIG_OVERRIDE) != 0 && patch(c).removed != null) {
            return patch(c).g != null && fold(c, truth);
          }
        }
      }
      return false;
    }
    Patch p = patch(el);
    Object guess = rt.lanes.latestOf(el);
    if (!older && p.g != null) {
      return fold(el, truth);
    }
    if (!shape.isArray(truth) || !isObject(guess) || !shape.isArray(guess)) {
      return true;
    }
    Function<Object, Object> keyFn = t.fam != null ? t.fam.key : null;
    if (keyFn == null) {
      return false;
    }
    List<Object> truthRows = new ArrayList<>();
    int tn = shape.length(truth);
    for (int i = 0; i < tn; i++) {
      truthRows.add(shape.get(truth, i));
    }
    Map<Object, Object> byKey = new HashMap<>();
    for (Object r : truthRows) {
      Object k = shape.isWrappable(r) ? keyFn.apply(r) : null;
      if (k != null && !byKey.containsKey(k)) {
        byKey.put(k, r);
      }
    }
    Set<Object> used = new HashSet<>();
    Set<Object> removed = p.removed != null ? p.removed : new HashSet<>();
    List<Object> rows = new ArrayList<>();
    // The #4525 re-base: the lane's rows are the new question's answer now (F-1, #4536).
    if (p.g == null) {
      q.txOf(el).over = t.fam.node.x.transaction;
    }
    if (p.g == null || p.g == UNSET) {
      p.g = rowsOf(guess);
    }
    List<Object> guessedRows = (List<Object>) p.g;
    for (Object g : guessedRows) {
      Object k = shape.isWrappable(g) ? keyFn.apply(g) : null;
      Object tr = k != null ? byKey.get(k) : null;
      if (tr != null) {
        // The truth's row under the guessed one's key: the guessed row's target adopts it.
        StoreTarget gt = shape.isWrappable(g) ? stores.lookupTarget(stores.unwrapValue(g), t.fam) : null;
        if (gt != null && stores.lookupTarget(tr, t.fam) == null) {
          stores.reconcileState(tr, gt.px, keyFn, true);
        }
        rows.add(tr);
        used.add(k);
      } else if (!removed.contains(k)) {
        rows.add(g);
      }
    }
    for (Object r : truthRows) {
      Object k = shape.isWrappable(r) ? keyFn.apply(r) : null;
      if (k != null && !used.contains(k) && !removed.contains(k)) {
        rows.add(r);
      }
    }
    // Every index of the composition is the guess's from here: the record covers the frame.
    if (p.wk == null) {
      p.wk = new LinkedHashSet<>();
    }
    int n = Math.max(rows.size(), Math.max(shape.length(guess), shape.length(stores.committed(t))));
    for (int i = 0; i < n; i++) {
      p.wk.add(i);
    }
    p.wk.add(StoreShape.LENGTH);
    Transaction l = q.txOf(el);
    rt.lanes.setLaneValue(el, l, arrayOf(rows, truth));
    // The composition changed what the lane shows: the carrier's readers re-derive as the lane's work.
    if (!sameRows(rowsOf(guess), rows, t.fam)) {
      q.laneDirty(el, l);
    }
    return true;
  }

  /** Two row lists show the same through the proxies: each slot the same target or the same primitive. */
  private boolean sameRows(List<Object> a, List<Object> b, StoreFamily fam) {
    if (a.size() != b.size()) {
      return false;
    }
    StoreShape shape = shape();
    for (int i = 0; i < a.size(); i++) {
      Object x = stores.unwrapValue(a.get(i));
      Object y = stores.unwrapValue(b.get(i));
      if (shape.isWrappable(x)) {
        StoreTarget xt = stores.lookupTarget(x, fam);
        if (xt == null || xt != stores.lookupTarget(y, fam)) {
          return false;
        }
      } else if (!stores.same(x, y)) {
        return false;
      }
    }
    return true;
  }

  /** D-34 for a keyed arrangement guess: a current truth that differs folds into the hold beneath it. */
  private boolean fold(Node el, Object truth) {
    Scheduler q = s();
    Transaction l = q.txOf(el);
    // A guess the screen never showed is void at the correction; a keyed arrangement guess carries its removed keys.
    if (!l.shown || patch(el).removed == null) {
      return false;
    }
    Object guess = rt.lanes.latestOf(el);
    if ((el.equals == null || !el.equals.test(guess, truth)) && !q.echoesHook.test(guess, truth)) {
      l.folded = this::foldedRead;
    } else {
      rt.lanes.truthConfirmed(l);
    }
    if (l.folded != null) {
      q.insertSubs(el);
    }
    q.joinFuture(Scheduler.resolveTx(l.parent != null ? l.parent : l));
    return true;
  }

  private Object foldedRead(Node c, Node el) {
    Scheduler q = s();
    if (c != null) {
      Node derive = el.host != null && el.host.fam() != null ? el.host.fam().node() : null;
      if ((el.config & CONFIG_GUESS) == 0 || q.passLane != null || c.type == EFFECT_RENDER
        || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0 || derive == c) {
        return NOT_PENDING;
      }
      Transaction l = q.txOf(el);
      rt.core.joinPassAt(c, Scheduler.resolveTx(l.parent != null ? l.parent : l));
    }
    if (el.pendingValue != NOT_PENDING) {
      return el.pendingValue;
    }
    return rt.lanes.covered(el);
  }

  /** A derive's landing meets the rows the guesses hold: each is reconciled into its row (D-34, #4329). */
  @Override public void pair(StoreTarget t, Object pb) {
    StoreShape shape = shape();
    Function<Object, Object> keyFn = t.fam.key;
    if (t.fam.overlaid == null) {
      return;
    }
    for (StoreTarget o : new ArrayList<>(t.fam.overlaid)) {
      StoreTarget c = o;
      while (c != null && c.u != t) {
        c = c.u;
      }
      if (c == null) {
        continue;
      }
      Object k = keyFn != null ? keyFn.apply(c.v) : null;
      Object nv = null;
      if (shape.isArray(pb)) {
        if (k != null) {
          for (Object r : rowsOf(pb)) {
            if (shape.isWrappable(r) && Stores.sameKey(keyFn.apply(r), k)) {
              nv = r;
              break;
            }
          }
        }
      } else {
        nv = shape.get(pb, c.pk);
      }
      if (shape.isWrappable(nv) && stores.lookupTarget(nv, t.fam) == null) {
        stores.reconcileState(nv, c.px, keyFn, true);
      }
    }
    // While guesses are out, every row of a keyed list keeps its target under the derive's new object with its key
    // (#4738): a row whose guess settled can be guessed again, on the committed row's target.
    if (keyFn != null && shape.isArray(pb) && !t.fam.overlaid.isEmpty()) {
      Map<Object, Object> byKey = null;
      for (Object ov : rowsOf(stores.committed(t))) {
        StoreTarget rt0 = shape.isWrappable(ov) ? stores.lookupTarget(ov, t.fam) : null;
        if (rt0 == null) {
          continue;
        }
        if (byKey == null) {
          byKey = new HashMap<>();
          for (Object r : rowsOf(pb)) {
            if (shape.isWrappable(r)) {
              byKey.put(keyFn.apply(r), r);
            }
          }
        }
        Object k = keyFn.apply(ov);
        Object nv = k == null ? null : byKey.get(k);
        if (nv != null && stores.lookupTarget(nv, t.fam) == null) {
          stores.reconcileState(nv, rt0.px, keyFn, true);
        }
      }
    }
  }

  /** A key a live guess changed reads through the carrier, tracked; NOT_PENDING for none. */
  @Override public Object read(StoreTarget t, Object key) {
    Node g = guessFor(t, key, false, true);
    if (g == null) {
      return NOT_PENDING;
    }
    Object frame = rt.core.read(g);
    Owner obs = rt.owners.getObserver();
    Node node = obs != null ? stores.getNode(t, key) : t.n != null ? t.n.get(key) : null;
    if (obs instanceof Node) {
      rt.graph.link(node, (Node) obs);
    }
    Object v = carrierValue(frame, key);
    if (obs == null) {
      noteRead(t, key, v, true);
    }
    return stores.serveKeyValue(t, key, v, false, node);
  }

  /** Presence of a key a live guess changed is the carrier frame's; null for none. */
  @Override public Boolean has(StoreTarget t, Object key) {
    Node g = guessFor(t, key, true, true);
    if (g == null) {
      return null;
    }
    Owner obs = rt.owners.getObserver();
    if (obs instanceof Node) {
      rt.graph.link(stores.getHasNode(t, key), (Node) obs);
    }
    return frameHas(rt.core.read(g), key);
  }

  @Override public Object descriptor(StoreTarget t, Object key) {
    Node g = guessFor(t, key, true, true);
    if (g == null) {
      return Stores.NO_GUESS;
    }
    Owner obs = rt.owners.getObserver();
    if (obs instanceof Node) {
      rt.graph.link(stores.getHasNode(t, key), (Node) obs);
    }
    Object frame = rt.core.read(g);
    if (!frameHas(frame, key)) {
      return null;
    }
    return new Object[] {carrierValue(frame, key)};
  }

  /** The frame an enumeration reads: the newest carrier's, tracked; NOT_PENDING for none. */
  @Override public Object frame(StoreTarget t) {
    Node g = newestGuess(t);
    return g == null ? NOT_PENDING : rt.core.read(g);
  }

  /** A key a live guess changed: its leaf is silent at a landing, the carrier's judgement tells its readers. */
  @Override public boolean covers(StoreTarget t, Object key, boolean shape) {
    return guessFor(t, key, shape, false) != null;
  }

  /** A landing reaches each live carrier as the truth it is judged against: the frame as landed. */
  @Override public void judge(StoreTarget t, Object truth) {
    List<Node> kg = kgOf(t);
    if (kg == null) {
      return;
    }
    Object frame = shape().clone(truth);
    for (Node g : new ArrayList<>(kg)) {
      if ((g.config & (CONFIG_OVERRIDE | CONFIG_HELD)) != 0) {
        rt.core.setSignal(g, frame);
      }
    }
  }

  @Override public void sweep() {
    if (overlaidFamilies.isEmpty()) {
      return;
    }
    for (StoreFamily fam : new ArrayList<>(overlaidFamilies)) {
      Set<StoreTarget> set = fam.overlaid;
      for (StoreTarget t : new ArrayList<>(set)) {
        List<Node> kg = kgOf(t);
        List<Node> live = new ArrayList<>();
        if (laneNodeOf(t, false) == null) {
          set.remove(t);
          if (kg != null) {
            for (Node g : kg) {
              if ((g.config & CONFIG_HELD) != 0 || g.pendingValue != NOT_PENDING) {
                live.add(g);
              }
            }
          }
        } else if (kg != null) {
          // A carrier that is no lane's and no hold's is done.
          for (Node g : kg) {
            if ((g.config & (CONFIG_OVERRIDE | CONFIG_GUESS | CONFIG_HELD)) != 0 || g.pendingValue != NOT_PENDING) {
              live.add(g);
            }
          }
        } else {
          continue;
        }
        if (live.isEmpty()) {
          carriers.remove(t);
        } else {
          carriers.put(t, live);
        }
      }
      if (set.isEmpty()) {
        overlaidFamilies.remove(fam);
      }
    }
  }

  /** The derive asks a new question over the family's guesses. */
  @Override public java.util.function.Predicate<Node> ask(StoreFamily fam) {
    Scheduler q = s();
    if (fam.overlaid == null || fam.overlaid.isEmpty()) {
      return null;
    }
    // A refresh re-asks the guesses' own question: a leaf lane work reads is kept off the wake (F-11, #4656).
    if (reasked(fam.node)) {
      return n -> n.subs.sub.x != null && n.subs.sub.x.transaction != null && n.subs.sub.x.transaction.lane;
    }
    long a = ~q.clock;
    for (StoreTarget g : fam.overlaid) {
      Node n = laneNodeOf(g, false);
      if (n != null) {
        if (q.passLane == null) {
          a = q.clock + 1;
        } else if (sameAction(q.txOf(n), q.passLane)) {
          return null;
        }
      }
    }
    fam.asked = a;
    if (a > 0) {
      q.joinFuture(null);
    }
    return null;
  }

  /** A read of an optimistic family whose derive is pending is served the guesses that stand. */
  @Override public boolean stands(StoreTarget t) {
    Scheduler q = s();
    StoreFamily f = t.fam;
    Set<StoreTarget> o = f.overlaid;
    long a = f.asked;
    Node d = f.node;
    Node c = rt.core.context instanceof Node ? (Node) rt.core.context : null;
    if (o == null || o.isEmpty() || (a == q.clock + 1 && q.passLane == null && !o.contains(t)) || a == ~q.clock) {
      return false;
    }
    boolean j = heldC == c && heldAt == q.clock;
    if (c != null && Stores.linkedTo(c, d)) {
      return j && heldV > 1;
    }
    if (rebasing(t) && c != null && (c.statusFlags & STATUS_PENDING) != 0) {
      if (c.type == EFFECT_RENDER) {
        rt.core.read(d);
      } else {
        if (!j) {
          heldC = c;
          heldAt = q.clock;
          heldV = held(c, new HashSet<>()) ? 1 : 0;
        }
        if (heldV != 0) {
          if (d.x != null && d.x.transaction != null) {
            rt.graph.link(d, c);
            q.staleReader(c, q.txOf(d));
            heldV = 2;
          } else {
            rt.core.read(d);
          }
        }
      }
    } else if (c != null && c.type != EFFECT_RENDER && d.x != null && d.x.reask && d.x.transaction != null
      && !ownFlight(o, q.txOf(d))) {
      // #4786: guesses of one action standing in for a refresh another action holds leave the pass derived from that
      // refetch: a stale reader of it, so the refetch's hold keeps waiting for it (F-11, A15).
      rt.graph.link(d, c);
      q.staleReader(c, q.txOf(d));
    }
    return true;
  }

  /** A guess in o (live or held) belongs to the transaction f that holds the flight it stands in for. */
  private boolean ownFlight(Set<StoreTarget> o, Transaction f) {
    Scheduler q = s();
    for (StoreTarget t : o) {
      List<Node> all = new ArrayList<>(kgOf(t) != null ? kgOf(t) : Collections.emptyList());
      all.add(t.dk);
      for (Node n : all) {
        if (n != null && n.x != null && n.x.transaction != null) {
          Transaction l = q.txOf(n);
          if (Scheduler.resolveTx(l.parent != null ? l.parent : l) == f) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** A carrier of t holds a list guess written over a flight (the #4525 mark). */
  private boolean rebasing(StoreTarget t) {
    List<Node> kg = kgOf(t);
    if (kg != null) {
      for (Node g : kg) {
        if (patch(g).g == null) {
          return true;
        }
      }
    }
    return false;
  }

  @Override public void park(Transaction t) {
    Scheduler q = s();
    for (StoreFamily fam : new ArrayList<>(overlaidFamilies)) {
      if (fam.asked == q.clock + 1) {
        for (StoreTarget g : new ArrayList<>(fam.overlaid)) {
          Node n = laneNodeOf(g, true);
          if (n != null) {
            q.merge(q.txOf(n).parent, t);
          }
        }
      }
    }
  }

  private boolean held(Node c, Set<Node> seen) {
    for (Link s = c.subs; s != null; s = s.nextSub) {
      Node r = s.sub;
      if ((r.statusFlags & STATUS_PENDING) != 0
        && (r.type == EFFECT_RENDER || (r.type == 0 && seen.add(r) && held(r, seen)))) {
        return true;
      }
    }
    return false;
  }

  private static boolean sameAction(Transaction a, Transaction b) {
    return Scheduler.resolveTx(a.parent) == Scheduler.resolveTx(b.parent);
  }

  private void markOverlaid(StoreTarget t) {
    StoreFamily fam = t.fam;
    if (fam.overlaid == null) {
      fam.overlaid = new LinkedHashSet<>();
    }
    fam.overlaid.add(t);
    overlaidFamilies.add(fam);
  }

  /** The node of t carrying a lane value, if any: a guess carrier or the deep witness (read: one a reader's last pass read). */
  private Node laneNodeOf(StoreTarget t, boolean read) {
    List<Node> all = new ArrayList<>(kgOf(t) != null ? kgOf(t) : Collections.emptyList());
    all.add(t.dk);
    for (Node n : all) {
      if (n != null && (n.config & CONFIG_OVERRIDE) != 0) {
        if (!read) {
          return n;
        }
        for (Link s = n.subs; s != null; s = s.nextSub) {
          if (s.gen == s.sub.depGen) {
            return n;
          }
        }
      }
    }
    return null;
  }
}
