package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import j2act.internal.signals.StoreTarget.StoreFamily;
import j2act.internal.signals.Stores.LaneView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Port of @solidjs/signals store/optimistic.ts at 9449845: optimistic stores on lanes. A user's write to an
 * optimistic store is a guess on the written key's node, a presence guess on its presence node and, when
 * membership or order changed, an arrangement guess on the container node. Lanes do the rest: the guess
 * shows under the action's hold, a landing beneath it stages as truth and confirms it when equal, and the
 * action's end dissolves it. The committed backing is never touched by a guess.
 */
final class StoreOptimistic implements Stores.OptHooks {
  private final Runtime rt;
  private final Stores stores;
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
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  private StoreShape shape() {
    return stores.shape();
  }

  private boolean isObject(Object v) {
    return v != null && shape().isObject(v);
  }

  /** The backing a writer composes on: the committed one, a chained store's as its own writer sees it. */
  private Object writerBacking(StoreTarget t) {
    Object base = stores.committed(t);
    StoreTarget inner = base != null && shape().isObject(base) ? shape().targetOf(base) : null;
    return inner == null ? base : view(inner, writerBacking(inner), true);
  }

  /** The user's draft: a clone of the view the user saw, the committed frame with the lanes' values and the tick's own guesses. */
  @Override public Object draft(StoreTarget t) {
    if (t.pb != null) {
      optStaged.put(t, t.pb);
    }
    return stores.cloneRaw(view(t, writerBacking(t), true), t);
  }

  private void guess(Node node, Object nv) {
    rt.lanes.optimisticWrite(node, nv, null);
  }

  /** A chained link's node follows the inner store: before a guess its value is what the inner store shows. */
  private void follow(Object inner, Object base, Node node, Object key, boolean presence) {
    if (inner != null) {
      node.value = presence ? (Object) shape().has(base, key) : stores.unwrapValue(shape().get(base, key));
    }
  }

  private Object resolved(StoreTarget t, Object inner, Object key, Object raw) {
    return stores.unwrapValue(inner != null && isObject(raw) ? stores.resolveChainedRaw(t, key, raw) : raw);
  }

  /** A user setter's writes on an optimistic family, as guesses. */
  @Override public Object writes(StoreTarget t, Object pb) {
    StoreShape shape = shape();
    Object base = writerBacking(t);
    Object old = view(t, base, true);
    boolean isArr = shape.isArray(pb);
    Object inner = t.ch ? t.v : null;
    boolean structural = false;
    for (Object key : shape.keys(pb)) {
      Object nv = resolved(t, inner, key, shape.get(pb, key));
      if (!shape.has(old, key)) {
        Node node = stores.getNode(t, key);
        follow(inner, base, node, key, false);
        guess(node, nv);
        Node hasNode = stores.getHasNode(t, key);
        follow(inner, base, hasNode, key, true);
        guess(hasNode, true);
        structural = true;
      } else {
        Object ov = resolved(t, inner, key, shape.get(old, key));
        if (!stores.same(ov, nv) && !stores.targetsEqual(ov, nv)) {
          Node node = stores.getNode(t, key);
          follow(inner, base, node, key, false);
          guess(node, nv);
          if (isArr) {
            structural = true;
          }
        }
      }
    }
    for (Object key : shape.keys(old)) {
      if (shape.has(pb, key)) {
        continue;
      }
      Node node = stores.getNode(t, key);
      follow(inner, base, node, key, false);
      guess(node, shape.undefined());
      Node hasNode = stores.getHasNode(t, key);
      follow(inner, base, hasNode, key, true);
      guess(hasNode, false);
      structural = true;
    }
    if (isArr && shape.length(old) != shape.length(pb)) {
      Node len = stores.getNode(t, StoreShape.LENGTH);
      follow(inner, base, len, StoreShape.LENGTH, false);
      guess(len, shape.length(pb));
      structural = true;
    }
    if (structural) {
      Object rows = isArr ? rowsOf(pb) : new LinkedHashSet<>(shape.keys(pb));
      Set<Object> removed = null;
      Function<Object, Object> keyFn = t.fam.key;
      if (isArr && keyFn != null) {
        removed = new HashSet<>();
        Set<Object> kept = new HashSet<>();
        Map<Object, Boolean> truth = shape.identityMap();
        for (Object r : rowsOf(base)) {
          if (isObject(r)) {
            truth.put(r, Boolean.TRUE);
          }
        }
        for (Object r : rowsOf(pb)) {
          Object k = shape.isWrappable(r) ? keyFn.apply(r) : null;
          if (k != null) {
            kept.add(k);
          }
        }
        for (Object raw : rowsOf(old)) {
          Object k = shape.isWrappable(raw) ? keyFn.apply(raw) : null;
          if (k != null && (!kept.contains(k) || truth.containsKey(raw))) {
            removed.add(k);
          }
        }
      }
      LaneView view = new LaneView(rows, base, removed);
      Node d = t.fam.node;
      if (removed != null && d != null && (d.statusFlags & STATUS_PENDING) != 0 && (d.flags & REACTIVE_REASK) == 0) {
        view.g = null;
      }
      guess(stores.getContainerNode(t), view);
    }
    if (t.dk != null) {
      guess(t.dk, new Object());
    }
    markOverlaid(t);
    return optStaged.remove(t);
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

  /** An arrangement as rows or keys, from a guess or from a backing. */
  private Object arrangementOf(Object x) {
    if (x instanceof LaneView) {
      return ((LaneView) x).rows;
    }
    return shape().isArray(x) ? (Object) rowsOf(x) : new LinkedHashSet<>(shape().keys(x));
  }

  @Override public boolean arrangement(StoreTarget t, Object a, Object b) {
    if (!(a instanceof LaneView) && !(b instanceof LaneView)) {
      return false;
    }
    return arrangementEqual(arrangementOf(a), arrangementOf(b), t.fam);
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

  @SuppressWarnings("unchecked")
  private boolean arrangementEqual(Object a, Object b, StoreFamily fam) {
    if (a instanceof List != b instanceof List) {
      return false;
    }
    if (a instanceof List) {
      List<Object> la = (List<Object>) a;
      List<Object> lb = (List<Object>) b;
      if (la.size() != lb.size()) {
        return false;
      }
      for (int i = 0; i < la.size(); i++) {
        if (!rowSame(la.get(i), lb.get(i), fam)) {
          return false;
        }
      }
      return true;
    }
    Set<Object> ka = (Set<Object>) a;
    Set<Object> kb = (Set<Object>) b;
    return ka.size() == kb.size() && kb.containsAll(ka);
  }

  /** A user's reconcile on an optimistic family: the keyed diff is written into the draft. */
  @Override public void reconcile(Object draft, Object incoming, Function<Object, Object> keyFn) {
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
            reconcile(match, nv, keyFn);
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
          reconcile(cur, nv, keyFn);
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

  /** Solid's GlobalQueue._slotTake. */
  private Object slotTake(Node n, boolean back) {
    StoreTarget t = n.host instanceof StoreTarget ? (StoreTarget) n.host : null;
    if (back) {
      Object k = n.key;
      markOverlaid(t);
      return k == Stores.TRACK ? stores.getContainerNode(t) : n.equals == Node.IS_EQUAL ? stores.getHasNode(t, k) : stores.getNode(t, k);
    }
    Scheduler q = s();
    Node derive = t != null && t.fam != null ? t.fam.node : null;
    Transaction d = derive != null && derive.x != null ? derive.x.transaction : null;
    Transaction g = q.txOf(n);
    if (d == null || !d.lane || sameAction(d, g)) {
      return null;
    }
    for (Node m : nodesOf(t)) {
      if (m != null && (m.config & CONFIG_GUESS) != 0 && q.txOf(m) == g) {
        rt.lanes.take(m, d, m != n);
      }
    }
    return Boolean.TRUE;
  }

  private static Object laneG(StoreTarget t) {
    Node k = t != null ? t.k : null;
    Object lane = k != null && k.x != null ? k.x.lane : null;
    return lane instanceof LaneView ? ((LaneView) lane).g : LaneView.UNSET;
  }

  /** Solid's GlobalQueue._laneRebase: a truth landing on a guess. */
  @SuppressWarnings("unchecked")
  private boolean laneRebase(Node el, Object truth, boolean older) {
    Scheduler q = s();
    StoreShape shape = shape();
    StoreTarget t = el.host instanceof StoreTarget ? (StoreTarget) el.host : null;
    if (!older && laneG(t) != null) {
      return fold(el, truth);
    }
    if (t == null || el.key != Stores.TRACK) {
      return true;
    }
    Object lane = el.x.lane;
    if (!(lane instanceof LaneView)) {
      return false;
    }
    LaneView view = (LaneView) lane;
    if (!shape.isArray(truth) || !(view.rows instanceof List)) {
      view.base = truth;
      return false;
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
    Set<Object> removed = view.removed;
    List<Object> rows = new ArrayList<>();
    if (view.g == null) {
      q.txOf(el).over = t.fam.node.x.transaction;
    }
    if (view.g == null || view.g == LaneView.UNSET) {
      view.g = view.rows;
    }
    List<Object> guessed = (List<Object>) view.g;
    for (Object g : guessed) {
      Object k = shape.isWrappable(g) ? keyFn.apply(g) : null;
      Object tr = k != null ? byKey.get(k) : null;
      if (tr != null) {
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
    view.rows = rows;
    view.base = rows;
    Transaction lane2 = q.txOf(el);
    Object base = stores.committed(t);
    Map<Object, Node> nodes = t.n;
    for (int i = 0; i < rows.size(); i++) {
      Node node = nodes != null ? nodes.get(i) : null;
      if (node == null) {
        if (!sameRow(shape.get(base, i), rows.get(i), keyFn)) {
          laneSet(stores.getNode(t, i), rows.get(i), lane2);
        }
      } else if (!sameRow((node.config & CONFIG_OVERRIDE) != 0 ? node.x.lane : shape.get(base, i), rows.get(i), keyFn)) {
        laneSet(node, rows.get(i), lane2);
      }
    }
    if (nodes != null) {
      for (int i = rows.size();; i++) {
        Node node = nodes.get(i);
        if (node == null) {
          break;
        }
        laneSet(node, shape.undefined(), lane2);
      }
      Node len = nodes.get(StoreShape.LENGTH);
      if (len != null && ((len.config & CONFIG_OVERRIDE) != 0 || shape.length(base) != rows.size())) {
        laneSet(len, rows.size(), lane2);
      }
    }
    return true;
  }

  /** A current truth differing from a shown keyed arrangement guess folds beneath it instead of voiding it (D-34). */
  private boolean fold(Node el, Object truth) {
    Scheduler q = s();
    StoreTarget t = el.host instanceof StoreTarget ? (StoreTarget) el.host : null;
    Node k = t != null ? t.k : null;
    Transaction l = q.txOf(el);
    Object kLane = k != null && k.x != null ? k.x.lane : null;
    if (!l.shown || k == null || (k.config & CONFIG_OVERRIDE) == 0 || !(kLane instanceof LaneView)
      || ((LaneView) kLane).removed == null) {
      return false;
    }
    Object guess = el.x.lane;
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
    return (el.config & CONFIG_SLOT_NODE) != 0 ? q.slotCoveredHook.apply(el) : el.value;
  }

  private boolean sameRow(Object a, Object b, Function<Object, Object> keyFn) {
    if (stores.same(a, b)) {
      return true;
    }
    if (!shape().isWrappable(a) || !shape().isWrappable(b)) {
      return false;
    }
    Object ka = keyFn.apply(stores.unwrapValue(a));
    return ka != null && Stores.sameKey(ka, keyFn.apply(stores.unwrapValue(b)));
  }

  private void laneSet(Node node, Object value, Transaction lane) {
    Scheduler q = s();
    NodeExtension x = rt.core.ext(node);
    if ((node.config & CONFIG_OVERRIDE) == 0) {
      node.config |= CONFIG_OVERRIDE;
      q.list(node, lane);
    } else if (x.lane == value) {
      return;
    }
    x.lane = value;
    if (node.subs != null) {
      q.insertSubs(node);
      q.laneDirty(node, lane);
    }
  }

  /** A derive's landing meets the rows the guesses hold: each is reconciled into its row. */
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
  }

  @Override public Object view(StoreTarget t, Object src, boolean writer) {
    StoreShape shape = shape();
    if (!writer && !overlaid(t)) {
      return src;
    }
    Object out = null;
    Map<Object, Node> nodes = t.n;
    if (nodes != null) {
      for (Map.Entry<Object, Node> e : new ArrayList<>(nodes.entrySet())) {
        Object key = e.getKey();
        Node node = e.getValue();
        Object ov;
        if ((node.config & CONFIG_OVERRIDE) != 0) {
          ov = writer ? rt.lanes.laneValueOf(node) : shown(node);
        } else if (!writer || (ov = rt.lanes.pendingGuessOf(node)) == NOT_PENDING) {
          continue;
        }
        if (key == StoreShape.LENGTH && shape.isArray(src)) {
          if (!Integer.valueOf(shape.length(src)).equals(ov)) {
            out = out != null ? out : shape.clone(src);
            shape.set(out, StoreShape.LENGTH, ov);
          }
        } else if (!stores.same(shape.get(src, key), ov) || !shape.has(src, key)) {
          out = out != null ? out : shape.clone(src);
          shape.set(out, key, ov);
        }
      }
    }
    Map<Object, Node> has = t.h;
    if (has != null) {
      for (Map.Entry<Object, Node> e : new ArrayList<>(has.entrySet())) {
        Node node = e.getValue();
        Object pv;
        if ((node.config & CONFIG_OVERRIDE) != 0) {
          pv = writer ? rt.lanes.laneValueOf(node) : shown(node);
        } else if (!writer || (pv = rt.lanes.pendingGuessOf(node)) == NOT_PENDING) {
          continue;
        }
        Object current = out != null ? out : src;
        if (!Signals.truthy(pv) && shape.has(current, e.getKey())) {
          out = out != null ? out : shape.clone(src);
          shape.delete(out, e.getKey());
        }
      }
    }
    return out != null ? out : src;
  }

  private Object shown(Node n) {
    return rt.core.untrack(() -> rt.core.read(n));
  }

  @Override public Boolean has(StoreTarget t, Object key) {
    if (!overlaid(t)) {
      return null;
    }
    Node h = t.h != null ? t.h.get(key) : null;
    if (h != null && (h.config & CONFIG_OVERRIDE) != 0) {
      return Signals.truthy(shown(h));
    }
    Node n = t.n != null ? t.n.get(key) : null;
    if (n != null && (n.config & CONFIG_OVERRIDE) != 0) {
      return shown(n) != shape().undefined();
    }
    return null;
  }

  @Override public Object descriptor(StoreTarget t, Object key) {
    Boolean present = has(t, key);
    if (present == null) {
      return Stores.NO_GUESS;
    }
    if (!present) {
      return null;
    }
    Node n = t.n != null ? t.n.get(key) : null;
    return new Object[] {n != null && (n.config & CONFIG_OVERRIDE) != 0 ? shown(n) : shape().get(t.v, key)};
  }

  @Override public void sweep() {
    if (overlaidFamilies.isEmpty()) {
      return;
    }
    for (StoreFamily fam : new ArrayList<>(overlaidFamilies)) {
      Set<StoreTarget> set = fam.overlaid;
      for (StoreTarget t : new ArrayList<>(set)) {
        if (laneNodeOf(t, false) == null) {
          set.remove(t);
          if (t.ch) {
            rebaseChained(t);
          }
          Node k = t.k;
          if (k != null && k.value instanceof LaneView) {
            k.value = ((LaneView) k.value).base;
          }
        }
      }
      if (set.isEmpty()) {
        overlaidFamilies.remove(fam);
      }
    }
  }

  /** A chained target's guesses ended: its nodes take the inner store's values again, and tell whoever read a difference. */
  private void rebaseChained(StoreTarget t) {
    Scheduler q = s();
    StoreShape shape = shape();
    Object inner = t.v;
    boolean changed = false;
    if (t.n != null) {
      for (Map.Entry<Object, Node> e : new ArrayList<>(t.n.entrySet())) {
        Node node = e.getValue();
        Object cur = rt.core.untrack(() -> stores.unwrapValue(shape.get(inner, e.getKey())));
        if (node.equals != null && node.equals.test(node.value, cur)) {
          continue;
        }
        node.value = cur;
        if (node.subs != null && node.notifiedAt != q.notifyEpoch) {
          q.insertSubs(node);
          changed = true;
        }
      }
    }
    if (t.h != null) {
      for (Map.Entry<Object, Node> e : new ArrayList<>(t.h.entrySet())) {
        Node node = e.getValue();
        Object cur = rt.core.untrack(() -> shape.has(inner, e.getKey()));
        if (cur.equals(node.value)) {
          continue;
        }
        node.value = cur;
        if (node.subs != null && node.notifiedAt != q.notifyEpoch) {
          q.insertSubs(node);
          changed = true;
        }
      }
    }
    Node k = t.k;
    if (k != null && k.subs != null && k.value instanceof LaneView && k.notifiedAt != q.notifyEpoch) {
      Object rows = rt.core.untrack(() -> arrangementOf(inner));
      if (!arrangementEqual(((LaneView) k.value).rows, rows, t.fam)) {
        q.insertSubs(k);
        changed = true;
      }
    }
    if (changed) {
      q.schedule();
    }
  }

  /** The derive asks a new question over the family's guesses. */
  @Override public void ask(StoreFamily fam) {
    Scheduler q = s();
    if ((fam.node.flags & REACTIVE_REASK) != 0 || fam.overlaid == null || fam.overlaid.isEmpty()) {
      return;
    }
    long a = ~q.clock;
    for (StoreTarget g : fam.overlaid) {
      Node n = laneNodeOf(g, false);
      if (n != null) {
        if (q.passLane == null) {
          a = q.clock + 1;
        } else if (sameAction(q.txOf(n), q.passLane)) {
          return;
        }
      }
    }
    fam.asked = a;
    if (a > 0) {
      q.joinFuture(null);
    }
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
    if (laneG(t) == null && c != null && (c.statusFlags & STATUS_PENDING) != 0) {
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
    }
    return true;
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

  private static List<Node> nodesOf(StoreTarget t) {
    List<Node> all = new ArrayList<>();
    all.add(t.k);
    all.add(t.dk);
    if (t.n != null) {
      all.addAll(t.n.values());
    }
    if (t.h != null) {
      all.addAll(t.h.values());
    }
    return all;
  }

  private static Node laneNodeOf(StoreTarget t, boolean read) {
    for (Node n : nodesOf(t)) {
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

  private static boolean overlaid(StoreTarget t) {
    StoreFamily fam = t.fam;
    return fam != null && fam.opt && fam.overlaid != null && fam.overlaid.contains(t);
  }

  /** `latest()`'s view: the lanes' latest values. */
  Object latestView(StoreTarget t, Object src) {
    return view(t, src, true);
  }
}
