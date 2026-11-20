package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.StatusError;
import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Port of @solidjs/signals boundaries.ts at 9449845: Loading and Errored. Left out: the dev reports of an
 * `on` that can never show its fallback and of an accessor passed to `on`.
 */
final class Boundaries {
  static final Object BOUNDARY = new Object();
  static final Object REVEAL = new Object();

  /** Solid's Boundary. */
  static final class Boundary {
    int type;
    Boundary parent;
    Owner owner;
    Node tree;
    Node output;
    final Map<Node, Node> readers = new LinkedHashMap<>();
    boolean initialized;
    boolean armed;
    Transaction armLane;
    Transaction lane;
    boolean fallback;
    Set<Node> runs;
    Reveal.Controller reveal;
    boolean gated;
    boolean collapsed;
    /** A tab's root while nobody can see it: it catches everything pending under it (ADR 0033). */
    boolean detached;
    Node error;
    Function<Boundary, Object> show;
    Node fb;
    /** A new description came while the fallback was not showing: it renders again before it next does. */
    boolean fbStale;
    Node onNode;
    /** Its `on` has run, so what it reads changing now means a new subject. */
    boolean onMounted;
    /** It runs because of a new description, so only a value that differs means a new subject. */
    boolean onRedescribed;
  }

  private final Runtime rt;
  private final Set<Boundary> collecting = new LinkedHashSet<>();
  private final Map<Node, Boundary> byOutput = new java.util.IdentityHashMap<>();
  private final Set<Owner> leftBehind = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

  Boundaries(Runtime rt) {
    this.rt = rt;
    Scheduler q = rt.scheduler;
    q.catchHook = (node, flags, error) -> truthy(catchStatus(node, flags, error));
    q.freshHook = (node, held) -> !truthy(held)
      ? ((node.statusFlags & STATUS_UNINITIALIZED) != 0 ? catchStatus(node, STATUS_PENDING, null) : armedOver(node))
      : Boolean.FALSE;
    q.hiddenHook = r -> hiddenBy(r) != null || detachedOver(r) || leftBehind(r);
    q.boundarySeamHook = this::boundarySeam;
    q.heldRunHook = this::heldRun;
  }

  private static boolean truthy(Object o) {
    return o != null && !Boolean.FALSE.equals(o);
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  private Boundary boundaryOf(Owner node) {
    return (Boundary) node.ctx.get(BOUNDARY);
  }

  /**
   * The statuses that keep reader r of b: b's own and pending and, for a loading boundary behind an error fallback,
   * the error of a reader directly under it: there is nothing to reveal, the error shows above (#4870).
   */
  private int keeps(Boundary b, Node r) {
    return b.type | STATUS_PENDING | (behindError(b.parent) && boundaryOf(r) == b ? STATUS_ERROR : 0);
  }

  /** The nearest loading boundary at or above b catches what is pending (#4926). */
  private boolean freshAbove(Boundary b) {
    return b != null && ((b.type & STATUS_PENDING) != 0 ? isCollecting(b) : freshAbove(b.parent));
  }

  /** An error boundary at or above {@code b} shows its fallback: content under it is not on screen. */
  private static boolean behindError(Boundary b) {
    return b != null && ((b.type == STATUS_ERROR && b.fallback) || behindError(b.parent));
  }

  /**
   * Catches what reaches it: an error boundary always; a loading one until it has shown content, or while re-armed.
   * Behind an error fallback nothing is on screen, so the error boundary's recovery re-arms the subtree (#4627).
   */
  private boolean isCollecting(Boundary b) {
    return b.type == STATUS_ERROR || !b.initialized || b.armed || b.detached || behindError(b.parent);
  }

  /**
   * Not in Solid, which has one screen (ADR 0033, F-3): a tab's root is a boundary that shows nothing and
   * catches nothing while the tab can be seen. Once it cannot, what is pending under it holds nothing beyond it.
   */
  Owner createTabRoot() {
    Owner owner = rt.core.runWithOwner(null, rt.owners::createOwner);
    Boundary b = new Boundary();
    b.type = STATUS_PENDING;
    b.owner = owner;
    b.initialized = true;
    Map<Object, Object> context = new java.util.HashMap<>(owner.ctx);
    context.put(BOUNDARY, b);
    owner.ctx = context;
    b.show = none -> null;
    rt.core.runWithOwner(owner, () -> {
      b.tree = rt.core.computed(prev -> null, null);
      b.output = rt.core.computed(prev -> null, null);
      return null;
    });
    return owner;
  }

  void setDetached(Owner tabRoot, boolean detached) {
    Boundary b = boundaryOf(tabRoot);
    b.detached = detached;
    if (!detached) {
      // Readers it caught answer for themselves when their loads land.
      b.readers.clear();
      collecting.remove(b);
    }
    // A hold that started while this tab could be seen may have only this tab to wait for now.
    if (s().transactionsOpen()) {
      s().schedule();
    }
  }

  private void record(Boundary b, Node node, Node source) {
    b.readers.put(node, source);
    collecting.add(b);
  }

  private void caught(Boundary b, Node node, Throwable error, Node source) {
    if (b.type == STATUS_ERROR) {
      // The reader's selected outcome owns publication: a latest or optimistic lane is not lost converting its error
      // into fallback content.
      Scheduler q = s();
      Transaction lane = q.laneStageHook != null
        ? (q.passLane != null ? q.passLane
          : node.x != null && node.x.transaction != null && node.x.transaction.lane ? q.txOf(node) : null)
        : null;
      if (q.laneStageHook != null && !b.fallback) {
        b.lane = lane;
      }
      if (error == null && node.x != null) {
        error = node.x.error;
      }
      Node from = error instanceof StatusError ? ((StatusError) error).source
        : error instanceof NotReadyError ? ((NotReadyError) error).source : null;
      source = from != null ? from : node;
      if ((source.flags & REACTIVE_DISPOSED) != 0) {
        source = node;
      }
      Throwable cause = Errors.unwrapStatusError(error);
      // Later errors update the shown fallback through this signal: its message publishes with the swap.
      if (q.laneStageHook != null && lane != null) {
        q.laneStageHook.stage(b.error, lane, true, false);
      }
      rt.core.setSignal(b.error, cause);
      rt.reportClientError(cause, b.owner, source);
    } else if (!b.detached) {
      // A loading boundary that catches has not shown content: fresh until it does (D-39, #4627). A tab's root
      // only catches while nobody can see it, and stays shown.
      b.initialized = false;
    }
    record(b, node, source);
    // A loading boundary behind an error fallback that catches a pending holds nothing above it (D-39, #4627).
    if (b.armed) {
      flip(b);
    }
    if (!b.fallback) {
      redraw(b);
    }
    if (b.reveal != null) {
      b.reveal.evaluate();
    }
  }

  private void flip(Boundary b) {
    Scheduler q = s();
    b.initialized = false;
    b.lane = b.armLane;
    Node o = b.output;
    if (b.lane == null && (o.config & CONFIG_HELD) != 0 && q.txOf(o) != q.flushTransaction) {
      q.unhold(o);
    }
    if (b.lane != null && b.output != null) {
      q.laneStageHook.stage(b.output, b.lane, true, Boolean.FALSE);
    }
  }

  void redraw(Boundary b) {
    if (b.output != null && (b.output.flags & (REACTIVE_DISPOSED | REACTIVE_ZOMBIE)) == 0) {
      s().dirtyQueue.enqueueSub(b.output);
      s().schedule();
    }
  }

  private Object catchStatus(Node node, int flags, Throwable error) {
    if (flags == 0) {
      if (!collecting.isEmpty()) {
        for (Boundary b = boundaryOf(node); b != null; b = b.parent) {
          Node r = b.readers.get(node);
          if ((r == null || (r.statusFlags & keeps(b, node)) == 0) && b.readers.remove(node) != null
            && prune(b, 0) == 0) {
            if (b.fallback) {
              redraw(b);
            }
            if (b.reveal != null) {
              b.reveal.evaluate();
            }
          }
        }
      }
      return Boolean.FALSE;
    }
    for (Boundary b = boundaryOf(node); b != null; b = b.parent) {
      if ((b.type & flags) == 0) {
        if (b.fallback && b.type > flags) {
          record(b, node, node);
        }
        continue;
      }
      if (isCollecting(b)) {
        caught(b, node, error, node);
        return b.armed ? (Object) Integer.valueOf(CAUGHT_REARMED) : Boolean.TRUE;
      }
      record(b, node, node);
    }
    return Boolean.FALSE;
  }

  private Object armedOver(Node node) {
    for (Boundary b = boundaryOf(node); b != null; b = b.parent) {
      if ((b.type & STATUS_PENDING) != 0 && isCollecting(b)) {
        return b.armed;
      }
    }
    return Boolean.FALSE;
  }

  private Boundary hiddenBy(Node r) {
    return hiddenBy(r, null);
  }

  /** Solid's {@code 1} from hiddenBy: hidden, with no boundary to name. Only ever for a node that is not an effect. */
  private static final Boundary HIDDEN = new Boundary();

  /**
   * The nearest boundary on r's chain showing its fallback, if any. A boundary output passing a pending by (an error
   * boundary) is on screen only through the render effects it reaches, through memos or other error boundaries:
   * reaching none, or only effects behind fallbacks, it holds nothing (F-4, D-14, #4638). A node already walked is
   * not walked again.
   */
  private Boundary hiddenBy(Node r, Set<Node> seen) {
    for (Boundary b = boundaryOf(r); b != null; b = b.parent) {
      if (b.fallback) {
        return b;
      }
    }
    Boundary h = null;
    if (seen != null || (r.config & CONFIG_REDERIVE) != 0) {
      if (seen == null) {
        seen = new HashSet<>();
      }
      h = r.type != EFFECT_RENDER ? HIDDEN : null;
      for (Link s = r.subs; s != null; s = s.nextSub) {
        h = !seen.add(s.sub) ? HIDDEN : hiddenBy(s.sub, seen);
        if (h == null) {
          break;
        }
      }
    }
    return h;
  }

  /**
   * Not in Solid, whose router's old page goes with the transition (ADR 0033, F-3): what a navigation is leaving is on
   * screen until what replaces it comes, and must not keep that from coming.
   */
  void setLeftBehind(Owner owner, boolean behind) {
    if (behind) {
      leftBehind.add(owner);
    } else {
      leftBehind.remove(owner);
    }
    if (s().transactionsOpen()) {
      s().schedule();
    }
  }

  private boolean leftBehind(Node r) {
    if (leftBehind.isEmpty()) {
      return false;
    }
    for (Owner o = r; o != null; o = o.parent) {
      if (leftBehind.contains(o)) {
        return true;
      }
    }
    return false;
  }

  private boolean detachedOver(Node r) {
    for (Boundary b = boundaryOf(r); b != null; b = b.parent) {
      if (b.detached) {
        return true;
      }
    }
    return false;
  }

  private boolean heldRun(Node node) {
    Boundary b = hiddenBy(node);
    // A swap to the fallback still staged in a hold (an on re-arm the frame holds) has not reached the screen: the
    // content is still the display, and a run there applies now (D-17, F-1, #4987).
    // So is one whose content a guess lane carries beside it (#4999; lanes only).
    if (b == null || (b.output.config & (s().laneStageHook != null ? CONFIG_HELD | CONFIG_OVERRIDE : CONFIG_HELD)) != 0) {
      return false;
    }
    if (b.runs == null) {
      b.runs = new LinkedHashSet<>();
    }
    b.runs.add(node);
    return true;
  }

  private void release(Boundary b) {
    if (b.runs == null) {
      return;
    }
    for (Node n : b.runs) {
      int type = n.type;
      if (type == EFFECT_TRACKED) {
        Runnable run = n.run;
        s().enqueue(EFFECT_USER, ignored -> run.run());
      } else {
        if (n.boundRunEffect == null) {
          n.boundRunEffect = t -> rt.effects.runEffect(n, t);
        }
        s().enqueue(type, n.boundRunEffect);
      }
    }
    b.runs = null;
  }

  /**
   * {@code r}, held by error boundary {@code b} for the error {@code s} threw, is pending again after {@code s}
   * answered, and a loading boundary between them that has not shown content catches it: that boundary is its
   * display, beside the recovered content (D-39, F-4, #4627). Not while that boundary's swap is staged in a hold.
   */
  private boolean caughtBelow(Boundary b, Node r, Node s) {
    if ((s.statusFlags & (STATUS_ERROR | STATUS_PENDING)) == 0 && (r.statusFlags & STATUS_ERROR) == 0) {
      for (Boundary l = boundaryOf(r); l != null && l != b; l = l.parent) {
        if ((l.type & STATUS_PENDING) != 0 && isCollecting(l)) {
          return (l.output.config & CONFIG_HELD) == 0;
        }
      }
    }
    return false;
  }

  /**
   * {@code n} reads {@code s}, directly or through derivations wherever they live. Each upstream node is walked
   * once, so a shared upstream graph costs its size, not its paths (#4644).
   */
  private static boolean reads(Node n, Node s, Set<Node> seen) {
    for (Link l = n.deps; l != null; l = l.nextDep) {
      Node h = l.dep;
      if (h == s || (seen.add(h) && reads(h, s, seen))) {
        return true;
      }
    }
    return false;
  }

  /** `pass` is Solid's `boolean | 0`: -1 for undefined or false, 0 for 0, 1 for true. */
  int prune(Boundary b, int pass) {
    Scheduler q = s();
    int mask = b.type | STATUS_PENDING;
    for (Map.Entry<Node, Node> e : new ArrayList<>(b.readers.entrySet())) {
      Node r = e.getKey();
      Node src = e.getValue();
      if (((r.flags | src.flags) & REACTIVE_DISPOSED) == 0) {
        int k = keeps(b, r);
        if ((((r.statusFlags & k) != 0 || ((src.statusFlags & k) != 0 && reads(r, src, new HashSet<>())))
          && !((mask & STATUS_ERROR) != 0 && caughtBelow(b, r, src)))
          || ((mask & STATUS_ERROR) != 0 && (src.config & CONFIG_STAGED_ERROR) != 0)
          || ((r.config & CONFIG_VERDICT) != 0 && (r.flags & REACTIVE_IN_HEAP) != 0)) {
          if (pass == 0) {
            break;
          }
          continue;
        }
        if ((src != b.tree || b.armed) && (src.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD) {
          if (pass != 1 || !b.initialized) {
            continue;
          }
          q.joinFuture(q.txOf(src));
        }
      }
      b.readers.remove(r);
    }
    return b.readers.size();
  }

  boolean ready(Boundary b) {
    return b.initialized || ((b.tree.statusFlags & STATUS_PENDING) == 0
      && !((b.tree.config & CONFIG_HELD) != 0 && (b.output == null || (b.output.statusFlags & STATUS_UNINITIALIZED) != 0))
      && prune(b, -1) == 0);
  }

  private void boundarySeam() {
    Scheduler q = s();
    for (Boundary b : new ArrayList<>(collecting)) {
      int flags = b.output.flags;
      if ((flags & REACTIVE_DISPOSED) != 0) {
        collecting.remove(b);
        continue;
      }
      if ((flags & REACTIVE_ZOMBIE) != 0) {
        continue;
      }
      if (!b.initialized) {
        // A reader recorded in this walk is walked too, as a JS Map's iterator would reach it.
        List<Node> walk = new ArrayList<>(b.readers.keySet());
        Set<Node> seen = new HashSet<>(walk);
        for (int i = 0; i < walk.size(); i++) {
          Node r = walk.get(i);
          Iterable<Node> list = (r.statusFlags & STATUS_PENDING) != 0
            ? (r.x != null && r.x.pendingSources != null ? new ArrayList<>(r.x.pendingSources) : new ArrayList<>())
            : List.of(r);
          for (Node n : list) {
            if ((n.config & CONFIG_HELD) == 0) {
              for (Link d = n.deps; d != null; d = d.nextDep) {
                Node h = d.dep;
                if ((h.config & CONFIG_HELD) != 0 && (n == r || q.txOf(h).open == 0)) {
                  record(b, h, h);
                  if (seen.add(h)) {
                    walk.add(h);
                  }
                }
              }
            }
          }
        }
      }
      prune(b, -1);
      Node o = b.output;
      if ((b.type & STATUS_PENDING) != 0 && !b.initialized && (o.config & CONFIG_HELD) != 0) {
        boolean all = true;
        for (Node src : b.readers.values()) {
          if ((src.statusFlags & STATUS_PENDING) != 0 || (src.config & CONFIG_HELD) == 0 || q.txOf(src) != q.txOf(o)) {
            all = false;
            break;
          }
        }
        if (all) {
          b.readers.clear();
          b.initialized = true;
        }
      }
      b.armed = false;
      if (b.readers.isEmpty()) {
        collecting.remove(b);
        if (b.fallback) {
          redraw(b);
        }
        if (b.reveal != null) {
          b.reveal.evaluate();
        }
      }
    }
  }

  private void arm(Boundary b) {
    Scheduler q = s();
    if (!b.initialized) {
      if (b.fallback && b.lane == null && b.armLane == null && q.passLane != null) {
        b.armLane = q.passLane;
        flip(b);
        redraw(b);
      }
      return;
    }
    b.armed = true;
    b.output.config &= ~CONFIG_FRAME;
    Transaction lane = q.passLane;
    if (lane == null && q.running && (rt.core.context.config & CONFIG_VERDICT) != 0) {
      q.joinFuture(null);
      lane = rt.lanes.verdictLane(q.flushTransaction);
    }
    b.armLane = lane;
    collecting.add(b);
    int n = b.readers.size();
    q.heldUnder(b.owner, q.flushTransaction, r -> record(b, r, r));
    for (Node r : new ArrayList<>(b.readers.keySet())) {
      if (b.readers.size() != n || ((r.statusFlags & STATUS_PENDING) != 0 && (r.flags & REACTIVE_DISPOSED) == 0)) {
        for (Boundary a = b.parent; a != null; a = a.parent) {
          if ((a.type & STATUS_PENDING) != 0) {
            for (Node o : b.readers.keySet()) {
              a.readers.remove(o);
            }
          }
        }
        flip(b);
        redraw(b);
        return;
      }
    }
  }

  private void reset(Boundary b) {
    for (Node r : new LinkedHashSet<>(b.readers.values())) {
      if (r.fn != null && (r.flags & REACTIVE_DISPOSED) == 0) {
        rt.core.recompute(r, false);
      }
    }
    s().schedule();
  }

  Node createBoundary(int type, Node.Compute fn, Function<Boundary, Object> show, Supplier<?> onFn) {
    Owner owner = rt.owners.createOwner();
    Boundary b = new Boundary();
    b.type = type;
    b.parent = boundaryOf(owner);
    b.owner = owner;
    b.initialized = type == STATUS_ERROR;
    if (type == STATUS_ERROR) {
      Core.NodeOptions errorSignal = new Core.NodeOptions();
      errorSignal.ownedWrite = true;
      b.error = rt.core.signal(null, errorSignal);
    }
    b.show = show;
    Reveal.Controller controller = type == STATUS_PENDING ? (Reveal.Controller) owner.ctx.get(REVEAL) : null;
    if (rt.core.context != null) {
      rt.owners.cleanup(() -> {
        collecting.remove(b);
        if (controller != null) {
          controller.unregister(b);
        }
      }, rt.core.context);
    }
    if (onFn != null) {
      b.onNode = rt.core.runWithOwner(owner, () -> rt.core.computed(prev -> {
        Object read = null;
        try {
          read = onFn.get();
        } catch (Throwable e) {
          if (!(e instanceof NotReadyError)) {
            if (!s().notify((Node) rt.core.context, STATUS_ERROR, STATUS_ERROR, e)) {
              s().haltReactivity(e);
              throw Errors.<RuntimeException>sneak(e);
            }
            return null;
          }
        }
        if (b.onMounted && (!b.onRedescribed || !ValueEquality.same(prev, read))) {
          arm(b);
        }
        b.onMounted = true;
        b.onRedescribed = false;
        return read;
      }, null));
      b.onNode.config |= CONFIG_REDERIVE;
    }
    Map<Object, Object> context = new java.util.HashMap<>(owner.ctx);
    context.put(BOUNDARY, b);
    context.put(REVEAL, null);
    owner.ctx = context;
    Node tree = rt.core.runWithOwner(owner, () -> {
      Node c = rt.core.computed(fn, named("children"));
      return rt.core.computed(prev -> rt.flatten.apply(rt.core.read(c)), named("boundary"));
    });
    b.tree = tree;
    if (controller != null) {
      controller.register(b);
    }
    Node output = rt.core.computed(prev -> outputPass(b, tree, type), named("value"));
    output.config |= CONFIG_REDERIVE;
    tree.config |= CONFIG_FRAME;
    b.output = output;
    byOutput.put(output, b);
    rt.owners.cleanup(() -> byOutput.remove(output), owner);
    return output;
  }

  private static Core.NodeOptions named(String name) {
    Core.NodeOptions o = new Core.NodeOptions();
    o.name = name;
    return o;
  }

  private Object outputPass(Boundary b, Node tree, int type) throws Exception {
    Node self = (Node) rt.core.context;
    Scheduler q = s();
    // Keep the ahead swap's lane for recovery as well as for the fallback: else the answer parks in the input's hold.
    // A swap to the fallback staged in a hold, the content still on screen (D-17): a lane pass derives that content with
    // its guesses (D-33, F-1), the swap left staged (#4999). Lanes only.
    boolean shown = q.laneStageHook != null && !b.initialized && (self.config & CONFIG_HELD) != 0
      && !ValueEquality.same(self.value, b.fb != null ? b.fb.value : null);
    if (q.laneStageHook != null && b.type == STATUS_ERROR) {
      if (b.fallback && (self.config & CONFIG_OVERRIDE) != 0 && b.lane == null) {
        b.lane = q.txOf(self);
      }
      if (b.lane != null) {
        q.passLane = b.lane;
        self.flags |= REACTIVE_LANE_READ;
        b.lane = null;
      }
    }
    if (!b.armed && (self.config & CONFIG_HELD) == 0) {
      self.config |= CONFIG_FRAME;
    }
    if (b.gated) {
      rt.graph.link(tree, self);
      if (b.reveal != null) {
        b.reveal.evaluate();
      }
      if (b.gated) {
        if (b.collapsed) {
          b.fallback = true;
          return null;
        }
        return fallback(b);
      }
    }
    int status = tree.statusFlags & (STATUS_PENDING | STATUS_ERROR);
    if (status != 0) {
      rt.graph.link(tree, self);
      if ((status & type) != 0) {
        if (isCollecting(b)) {
          caught(b, tree, null, tree);
          return fallback(b);
        }
        record(b, tree, tree);
      }
      // An error boundary on its fallback is the display of everything under it: a retry pending there keeps the
      // fallback and holds nothing outside it (F-3, F-12).
      // Not when the loading boundary above has not shown content: this fallback was never on screen, and that
      // boundary catches the pending (F-4, #4926).
      if (type == STATUS_ERROR && b.fallback && !freshAbove(b)) {
        return fallback(b);
      }
      throw Errors.<RuntimeException>sneak(tree.x.error);
    }
    if (isCollecting(b)) {
      // Linked, which Solid does not do here (ADR 0033): a reader can fail rather than settle, and only the tree says so.
      if (!b.readers.isEmpty() && (self.statusFlags & STATUS_UNINITIALIZED) != 0 && (self.config & CONFIG_HELD) == 0) {
        rt.graph.link(tree, self);
        return fallback(b);
      }
      if (prune(b, 1) != 0 && !shown) {
        rt.graph.link(tree, self);
        return fallback(b);
      }
    }
    Object value;
    try {
      value = rt.core.read(tree);
    } catch (Throwable e) {
      if (e instanceof NotReadyError ? type == STATUS_PENDING : type == STATUS_ERROR) {
        if (isCollecting(b)) {
          caught(b, tree, e, tree);
          return fallback(b);
        }
        record(b, tree, tree);
      }
      throw Errors.<RuntimeException>sneak(e);
    }
    // So is a tree the read itself ran into a pending: an on re-armed by a guess showed the old content (#4856).
    if (isCollecting(b) && (tree.statusFlags & type & STATUS_PENDING) != 0) {
      caught(b, tree, null, tree);
    }
    if (isCollecting(b) && !b.readers.isEmpty()) {
      return shown && q.passLane != null ? value : fallback(b);
    }
    b.initialized = true;
    b.fallback = false;
    Node fb = b.fb;
    if (fb != null) {
      Runnable drop = () -> {
        if (!b.fallback && b.fb == fb) {
          if ((fb.statusFlags & STATUS_ERROR) != 0) {
            s().schedule();
          }
          rt.owners.dispose(fb);
          b.fb = null;
        }
      };
      if (!ValueEquality.same(b.output.value, fb.value)) {
        drop.run();
      } else {
        s().enqueue(EFFECT_RENDER, t -> drop.run());
      }
    }
    release(b);
    if (b.reveal != null) {
      Reveal.Controller controller = b.reveal;
      b.reveal = null;
      controller.evaluate();
    }
    return value;
  }

  private Object fallback(Boundary b) {
    Scheduler q = s();
    Node out = (Node) rt.core.context;
    if (b.lane != null) {
      q.passLane = b.lane;
      out.flags |= REACTIVE_LANE_READ;
      b.lane = null;
    }
    b.fallback = true;
    if (b.fb == null) {
      b.fb = rt.core.runWithOwner(out.parent, () -> {
        Core.NodeOptions lazy = new Core.NodeOptions();
        lazy.lazy = true;
        return rt.core.computed(prev -> b.show.apply(b), lazy);
      });
    }
    if (b.fbStale) {
      b.fbStale = false;
      rt.core.recompute(b.fb, false);
    }
    Transaction p = q.passLane;
    int f = out.flags;
    Object v = rt.core.read(b.fb);
    q.passLane = p;
    out.flags ^= (out.flags ^ f) & (REACTIVE_PROBED | REACTIVE_LANE_READ);
    return v;
  }

  /**
   * A fallback showing renders again; one that is not does so before it next shows. It is never dropped here: until
   * what replaces it commits, it can be on screen with the boundary no longer showing it, as in Solid.
   */
  boolean redescribed(Node output) {
    Boundary b = byOutput.get(output);
    if (b == null) {
      return false;
    }
    if (b.onNode != null) {
      // It reads what the new description says from now on, which alone is no change of subject.
      b.onRedescribed = true;
      s().dirtyQueue.enqueueSub(b.onNode);
      s().schedule();
    }
    if (b.fb == null) {
      return false;
    }
    if (b.fallback) {
      s().dirtyQueue.enqueueSub(b.fb);
      s().schedule();
      return true;
    }
    b.fbStale = true;
    return false;
  }

  boolean showsFallback(Node output) {
    Boundary b = byOutput.get(output);
    return b != null && b.fallback;
  }

  /** A boundary whose content is out of the way of its `on`, for a caller that has its own. */
  Node createLoadingBoundary(Node.Compute fn, Supplier<Object> fallback, Supplier<?> on) {
    return createBoundary(STATUS_PENDING, fn, b -> fallback.get(), on);
  }

  /** `fallback` gets the error's node, read for the latest error, and a reset. */
  Node createErrorBoundary(Node.Compute fn, BiFunction<Node, Runnable, Object> fallback) {
    return createBoundary(STATUS_ERROR, fn, b -> fallback.apply(b.error, () -> reset(b)), null);
  }
}
