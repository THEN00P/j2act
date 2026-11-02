package j2act.internal.signals;

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Node.Compute;
import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Port of @solidjs/signals core/core.ts at 9449845: recompute, read, and write. Left out: the shadow seat,
 * strict reads and other dev-only checks, attribution, hydration snapshots, store slot nodes and ids.
 */
final class Core {

  static final String PRIMITIVE_IN_FORBIDDEN_SCOPE_MESSAGE =
    "[PRIMITIVE_IN_FORBIDDEN_SCOPE] Cannot create reactive primitives inside createTrackedEffect or owner-backed onSettled";
  static final String REACTIVE_WRITE_IN_OWNED_SCOPE_SIGNAL_MESSAGE =
    "[REACTIVE_WRITE_IN_OWNED_SCOPE] Writing to reactive state inside an owned scope (component, computation) is not allowed."
      + " Move the write outside or set the `ownedWrite` option if this is intentional.";
  static final String REACTIVE_WRITE_IN_OWNED_SCOPE_REFRESH_MESSAGE =
    "[REACTIVE_WRITE_IN_OWNED_SCOPE] Calling refresh() inside an owned scope (component, computation) is not allowed."
      + " Move the invalidation outside pure computation.";
  static final String PRIMITIVE_IN_EFFECT_CALLBACK_MESSAGE =
    "[PRIMITIVE_IN_EFFECT_CALLBACK] Cannot create a memo, effect or root with no owner inside an effect callback.";

  /** Options a node is created with; Solid's NodeOptions. */
  static final class NodeOptions {
    java.util.function.BiPredicate<Object, Object> equals = Node.IS_EQUAL;
    boolean ownedWrite;
    Runnable unobserved;
    boolean lazy;
    boolean sync;
    boolean hasLoadingValue;
    Object loadingValue;
    int extraConfig;
    String name;
  }

  private final Runtime rt;
  boolean tracking;
  boolean stale;
  Owner context;
  private Object laneRoot;
  boolean spectating;
  boolean unflushedStaged;
  private final List<Node> promotedWrites = new ArrayList<>();
  /** Solid's `verdict`: the read while isPending or latest is open. Null otherwise. */
  java.util.function.BiFunction<Node, Node, Object> verdict;
  int disposalDepth;
  boolean inEffectCallback;
  /** Set by effect.ts. */
  java.util.function.Consumer<Node> effectStatusNotify;

  Core(Runtime rt) {
    this.rt = rt;
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  /** GlobalQueue._update. */
  void update(Node el) {
    Scheduler q = s();
    if (el.type == EFFECT_TRACKED) {
      q.dirtyQueue.deleteFromHeap(el);
      el.modified = true;
      Runnable run = el.run;
      q.enqueue(EFFECT_USER, type -> run.run());
    } else if ((el.flags & REACTIVE_ZOMBIE) != 0 && (el.flags & REACTIVE_LANE_DIRTY) == 0) {
      q.deferZombie(el);
    } else if (q.laneStageHook != null && (el.flags & REACTIVE_ZOMBIE) != 0 && laneFrameMember(el)) {
      q.dirtyQueue.deleteFromHeap(el);
      el.flags &= ~(REACTIVE_DIRTY | REACTIVE_CHECK | REACTIVE_LANE_DIRTY);
    } else {
      recompute(el, false);
    }
  }

  private boolean laneFrameMember(Node el) {
    Owner o = el.parent;
    while ((o.flags & REACTIVE_ZOMBIE) != 0) {
      o = o.parent;
    }
    return (o.config & (CONFIG_OVERRIDE | CONFIG_GUESS)) == CONFIG_OVERRIDE
      || (o instanceof Node && ((Node) o).type != 0 && o.x != null && o.x.transaction != null && o.x.transaction.lane);
  }

  void assertNotInEffectCallback() {
    if (inEffectCallback && context == null) {
      throw new IllegalStateException(PRIMITIVE_IN_EFFECT_CALLBACK_MESSAGE);
    }
  }

  private static boolean truthy(Object o) {
    return o != null && !Boolean.FALSE.equals(o);
  }

  void recompute(Node el, boolean create) {
    Scheduler q = s();
    q.bumpNotifyEpoch();
    int isEffect = el.type;
    List<Node> released = null;
    Transaction prevLane = q.passLane;
    Object prevMount = laneRoot;
    Object mount = null;
    if (q.laneStageHook != null) {
      laneRoot = create ? (prevLane != null ? prevLane : prevMount) : null;
      mount = laneRoot;
      Transaction next = null;
      if (((el.flags & REACTIVE_LANE_DIRTY) != 0 || (el.config & (CONFIG_OVERRIDE | CONFIG_GUESS)) == CONFIG_OVERRIDE)
        && el.x != null && el.x.transaction != null && el.x.transaction.lane) {
        next = el.x.transaction;
      } else if (create && prevLane != null && prevLane.parent.verdict != prevLane) {
        Node creator = creatorPass(context);
        if (creator != null && (creator.flags & REACTIVE_RECOMPUTING_DEPS) != 0) {
          next = prevLane;
        }
      }
      q.passLane = next;
    }
    boolean replaced = (el.config & CONFIG_STAGED_ERROR) != 0;
    if (!create) {
      q.dirtyQueue.deleteFromHeap(el);
      if (q.laneStageHook != null && isEffect == 0 && ((el.statusFlags & STATUS_PENDING) != 0
        || (el.config & (CONFIG_ASYNC | CONFIG_HELD)) == (CONFIG_ASYNC | CONFIG_HELD))) {
        el.config &= ~CONFIG_VERDICT;
      }
      if (el.x != null) {
        Object p = q.laneStageHook != null ? el.x.parked : null;
        if (p != null && p != el.x.inFlight) {
          el.x.parked = null;
        }
        el.x.inFlight = null;
        rt.async.releaseFlightTeardown(el);
      }
      if ((el.config & CONFIG_HELD) != 0) {
        Transaction tx = q.txOf(el);
        if (tx.lane || (el.config & CONFIG_LAGS) != 0) {
          // a lane's or a lagging source's own pass stays where it is
        } else if ((el.config & (CONFIG_VERDICT | (EFFECT_RENDER << 23))) == 0
          || (q.observeFlightHook != null && (el.config & (EFFECT_RENDER << 23)) == 0 && bornInto(el, tx))) {
          q.joinPassTx(tx, q.freshHook != null && truthy(q.freshHook.fresh(el, (el.statusFlags & STATUS_UNINITIALIZED) != 0)));
        } else if (tx != q.flushTransaction && (el.statusFlags & STATUS_UNINITIALIZED) == 0
          && (el.config & CONFIG_HELD_FRAME) == 0) {
          if (el.pendingValue != NOT_PENDING) {
            q.insertSubs(el);
          }
          el.pendingValue = NOT_PENDING;
          el.config &= ~CONFIG_HELD;
        }
      }
      if (isEffect == EFFECT_TRACKED || (el.config & CONFIG_STAGED) != 0) {
        List<Node> prev = rt.graph.deferredReleases;
        released = new ArrayList<>();
        rt.graph.deferredReleases = released;
        rt.owners.disposeChildren(el, false, false);
        rt.graph.deferredReleases = prev;
      } else if (el.firstChild != null || el.disposal != null) {
        parkChildren(el);
      }
      if ((el.config & CONFIG_STAGED_ERROR) != 0) {
        el.pendingValue = NOT_PENDING;
        el.config ^= CONFIG_STAGED_ERROR;
      }
    }
    int wasUninitialized = el.statusFlags & STATUS_UNINITIALIZED;
    Throwable outgoingError = (el.statusFlags & STATUS_ERROR) != 0 && el.x != null ? el.x.error : null;
    boolean wasPending = (el.statusFlags & STATUS_PENDING) != 0;
    Set<Node> outgoingPendingSources = wasPending && el.x != null ? el.x.pendingSources : null;
    if (outgoingPendingSources != null) {
      outgoingPendingSources = new java.util.LinkedHashSet<>(outgoingPendingSources);
    }
    boolean wasPendingSource = el.x != null && el.x.pendingSources != null && el.x.pendingSources.contains(el);
    boolean stagedErr = false;
    boolean reask = (el.flags & REACTIVE_REASK) != 0
      && !(((el.statusFlags & STATUS_PENDING) != 0 || (q.guessing && el.pendingValue != NOT_PENDING))
        && el.x != null && !el.x.reask);
    Owner oldcontext = context;
    context = el;
    el.depsTail = null;
    el.depGen++;
    int releaseAfter = el.flags & REACTIVE_RELEASE_AFTER;
    el.flags = REACTIVE_RECOMPUTING_DEPS | (el.flags & (REACTIVE_ZOMBIE | REACTIVE_REASK));
    el.time = q.clock;
    Object value = el.pendingValue != NOT_PENDING ? el.pendingValue
      : (el.config & CONFIG_OVERRIDE) != 0 && el.x.lane != NOT_PENDING ? el.x.lane : el.value;
    int oldHeight = el.height;
    boolean missedWake = false;
    boolean joined = false;
    boolean prevTracking = tracking;
    tracking = true;
    boolean isStaleEffect = isEffect != 0 && isEffect != EFFECT_USER;
    boolean prevStale = stale;
    if (isStaleEffect) {
      stale = true;
    }
    try {
      Object prevInFlight = el.x != null ? el.x.inFlight : null;
      Object fnResult = el.fn.apply(value);
      boolean isAsyncResult = Async.isAsync(fnResult);
      boolean selfRegistered = (el.x != null ? el.x.inFlight : null) != prevInFlight;
      value = selfRegistered || !isAsyncResult ? fnResult : rt.async.handleAsync(el, fnResult, null, null);
      if (!selfRegistered && !isAsyncResult) {
        if (el.x != null) {
          el.x.inFlight = null;
          if (q.holdWorkHook != null) {
            el.config &= ~CONFIG_ASYNC;
          }
        }
        el.loading = false;
      }
      if (el.statusFlags != 0 || el.x != null) {
        rt.async.clearStatus(el, create);
      }
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      boolean notReady = e instanceof NotReadyError;
      if (q.holdWorkHook != null && (!notReady || el.loading)) {
        el.config &= ~CONFIG_ASYNC;
      }
      if (notReady && el.loading) {
        rt.async.parkLoadingWindow(el, (NotReadyError) e);
      } else {
        if (notReady || !(stagedErr = rt.async.stageError(el, e, true))) {
          if (notReady) {
            ext(el).blocked = true;
          }
          rt.async.notifyStatus(el, notReady ? STATUS_PENDING : STATUS_ERROR, e);
          if (notReady && wasPendingSource && (el.x == null || el.x.inFlight == null)) {
            rt.async.settlePendingSource(el, el);
          }
        }
        if ((notReady || stagedErr) && outgoingPendingSources != null) {
          for (Node source : outgoingPendingSources) {
            if (source != el && !(el.x != null && el.x.pendingSources != null && el.x.pendingSources.contains(source)
              && ((source.statusFlags & STATUS_PENDING) != 0 || rt.async.retryReaches(el, source)))) {
              rt.async.settlePendingSource(el, source);
            }
          }
        }
      }
    } finally {
      tracking = prevTracking;
      if (q.laneStageHook != null) {
        laneRoot = prevMount;
      }
      if (isStaleEffect) {
        stale = prevStale;
      }
      missedWake = (el.flags & REACTIVE_MISSED_WAKE) != 0;
      int joinMask = mount != null
        ? (prevLane != null || isEffect != 0 || (el.statusFlags & STATUS_PENDING) == 0
          ? REACTIVE_JOINED | REACTIVE_STAGED_READ : 0)
        : REACTIVE_JOINED;
      joined = (el.flags & joinMask) != 0;
      if (q.observeFlightHook != null) {
        el.config = (el.config & ~CONFIG_VERDICT) | (el.flags & REACTIVE_PROBED);
      }
      el.flags = el.flags & (REACTIVE_ZOMBIE | REACTIVE_DISPOSED | REACTIVE_FRAME_READ | REACTIVE_STAGED_READ
        | REACTIVE_LANE_READ | REACTIVE_SCREEN_READ | REACTIVE_PROBE_UNANSWERED);
      context = oldcontext;
      if (released != null) {
        for (Node node : released) {
          rt.async.releaseIfSettledUnobserved(node);
        }
      }
      if (el.x != null) {
        el.x.reask = reask && (el.statusFlags & STATUS_PENDING) != 0;
      }
    }
    if ((el.flags & REACTIVE_DISPOSED) != 0) {
      rt.graph.clearDeps(el);
      rt.async.clearInFlight(el);
      if (q.laneStageHook != null) {
        q.passLane = prevLane;
      }
      return;
    }
    Transaction lane = q.laneStageHook == null ? null : q.passLane;
    boolean errored = (el.x != null && el.x.error != null) || stagedErr;
    if (q.laneStageHook != null && lane != null) {
      if (q.laneStageHook.stage(el, lane, create, errored)) {
        q.passLane = lane;
      } else {
        lane = null;
      }
    }
    if (!errored) {
      boolean unstaged = el.pendingValue == NOT_PENDING;
      Object compareValue = !unstaged ? el.pendingValue
        : (el.config & CONFIG_OVERRIDE) != 0 && el.x.lane != NOT_PENDING ? el.x.lane : el.value;
      boolean valueChanged = (unstaged && wasUninitialized != 0 && isEffect == 0)
        || (el.x != null && el.x.shownError != null) || replaced || !isSame(el, compareValue, value);
      if (isEffect != 0 && valueChanged
        && !((el.flags & REACTIVE_SCREEN_READ) != 0 && !el.modified && java.util.Objects.equals(el.prevValue, value))
        && unstaged) {
        el.modified = el.x == null || el.x.error == null;
        if (!create) {
          if (el.boundRunEffect == null) {
            el.boundRunEffect = type -> rt.effects.runEffect(el, type);
          }
          q.enqueue(isEffect, el.boundRunEffect, lane);
        }
      }
      if (q.laneOutcomeHook != null && !create && lane == null && (el.config & CONFIG_GUESS) != 0
        && q.laneOutcomeHook.outcome(el, value, errored)) {
        // the lane decided
      } else if (valueChanged) {
        if (lane != null) {
          if (isEffect != 0 && el.pendingValue == NOT_PENDING) {
            el.value = value;
          } else if (isEffect != 0 || lane.shown) {
            el.pendingValue = value;
          } else {
            el.x.lane = value;
          }
        } else if (create
          ? !((q.flushTransaction != null || q.passTx != null) && (joined || creatorJoined(oldcontext)))
          : isEffect != 0 && el.pendingValue == NOT_PENDING) {
          el.value = value;
        } else {
          el.pendingValue = value;
          if (outgoingError != null) {
            el.x.shownError = outgoingError;
          }
          if (create) {
            q.holdNode(el, q.flushTransaction != null ? q.flushTransaction : q.passTx);
            el.statusFlags |= STATUS_UNINITIALIZED;
            if (q.freshHook != null) {
              q.freshHook.fresh(el, mount == null ? q.flushTransaction : null);
            }
          }
        }
        if (el.subs != null) {
          q.insertSubs(el);
          if (lane != null) {
            q.laneDirty(el, lane);
          }
        }
      } else if (el.height != oldHeight) {
        for (Link l = el.subs; l != null; l = l.nextSub) {
          q.dirtyQueue.insertIntoHeapHeight(l.sub);
        }
      }
      if (!valueChanged && (el.x == null || el.x.error == null)) {
        if (outgoingError != null) {
          rt.async.settleErroredDependents(el, outgoingError);
        }
        if (outgoingPendingSources != null) {
          for (Node source : outgoingPendingSources) {
            if (source != el) {
              rt.async.settlePendingSource(el, source);
            }
          }
        }
      }
      if (wasPendingSource && (el.statusFlags & (STATUS_PENDING | STATUS_UNINITIALIZED)) == 0) {
        rt.async.settlePendingSource(el, el);
      }
    }
    if (create && lane == null && joined && (el.statusFlags & STATUS_PENDING) != 0) {
      Transaction t = q.flushTransaction != null ? q.flushTransaction : q.passTx;
      if (t != null && !t.lane) {
        boolean rearmed = isEffect != 0 && q.freshHook != null && Integer.valueOf(CAUGHT_REARMED).equals(q.freshHook.fresh(el, null));
        if (!rearmed) {
          q.holdNode(el, t);
        }
      }
    }
    if ((el.x == null || el.x.error == null) && el.pendingValue == NOT_PENDING && !(isEffect != 0 && el.modified)) {
      if (create || isEffect == EFFECT_TRACKED) {
        rt.graph.trimStaleDeps(el);
      } else {
        q.heldTrim(el);
      }
    }
    if (el.pendingValue != NOT_PENDING
      || (el.x != null && (el.x.pendingFirstChild != null || el.x.pendingDisposal != null))
      || ((el.statusFlags & (STATUS_PENDING | STATUS_UNINITIALIZED)) != 0 && (!create || (el.statusFlags & STATUS_PENDING) != 0))) {
      el.config |= CONFIG_STAGED;
      if (lane == null) {
        q.queuePendingNode(el);
      }
    }
    if (q.laneStageHook != null) {
      q.passLane = prevLane;
    }
    if (missedWake) {
      q.dirtyQueue.enqueueSub(el);
      q.schedule();
    }
    if (releaseAfter != 0) {
      rt.async.releaseIfSettledUnobserved(el);
    }
  }

  private boolean creatorJoined(Owner oldcontext) {
    Node creator = creatorPass(oldcontext);
    return creator != null && (creator.flags & REACTIVE_JOINED) != 0;
  }

  private void updateIfNecessary(Node el) {
    if ((el.flags & (REACTIVE_RECOMPUTING_DEPS | REACTIVE_DISPOSED)) != 0) {
      return;
    }
    if ((el.flags & REACTIVE_CHECK) != 0) {
      for (Link d = el.deps; d != null; d = d.nextDep) {
        Node dep = d.dep;
        if (dep.fn != null) {
          updateIfNecessary(dep);
        }
        if ((el.flags & REACTIVE_DIRTY) != 0) {
          break;
        }
      }
    }
    if ((el.flags & REACTIVE_DIRTY) != 0
      || (el.x != null && el.x.error != null && el.time < s().clock && el.x.inFlight == null)) {
      recompute(el, false);
    }
    el.flags = el.flags & (REACTIVE_IN_HEAP | REACTIVE_IN_HEAP_HEIGHT | REACTIVE_ZOMBIE | REACTIVE_DISPOSED
      | REACTIVE_MANUAL_WRITE | REACTIVE_FRAME_READ | REACTIVE_STAGED_READ | REACTIVE_LANE_READ | REACTIVE_SCREEN_READ
      | REACTIVE_PROBE_UNANSWERED);
  }

  Node computed(Compute fn, NodeOptions options) {
    Node self = new Node(rt);
    boolean loading = options != null && options.hasLoadingValue;
    self.config = (options != null && options.ownedWrite ? CONFIG_OWNED_WRITE : 0)
      | (context == null || (options != null && options.lazy) ? CONFIG_AUTO_DISPOSE : 0)
      | (options != null && options.sync ? CONFIG_SYNC : 0)
      | (options != null ? options.extraConfig : 0);
    self.equals = options != null ? options.equals : Node.IS_EQUAL;
    self.name = options != null ? options.name : null;
    self.fn = fn;
    self.value = loading ? options.loadingValue : null;
    self.parent = context;
    self.flags = options != null && options.lazy ? REACTIVE_LAZY : REACTIVE_NONE;
    self.statusFlags = loading ? 0 : STATUS_UNINITIALIZED;
    self.time = s().clock;
    self.loading = loading;
    if (options != null && options.unobserved != null) {
      ext(self).unobserved = options.unobserved;
    }
    setupComputedNode(self, options != null && options.lazy);
    return self;
  }

  NodeExtension ext(Owner el) {
    if (el.x == null) {
      el.x = new NodeExtension();
    }
    return el.x;
  }

  void joinPass(Node c, Node el) {
    Scheduler q = s();
    c.flags |= REACTIVE_JOINED;
    if ((el.statusFlags & STATUS_UNINITIALIZED) != 0 || c.type != EFFECT_RENDER) {
      q.joinPassTx(q.txOf(el), (c.type == 0 && (c.statusFlags & STATUS_UNINITIALIZED) != 0)
        || (q.freshHook != null && truthy(q.freshHook.fresh(c, null))));
    }
  }

  void joinPassAt(Node c, Transaction t) {
    Scheduler q = s();
    c.flags |= REACTIVE_JOINED;
    q.joinPassTx(t, (c.type == 0 && (c.statusFlags & STATUS_UNINITIALIZED) != 0)
      || (q.freshHook != null && truthy(q.freshHook.fresh(c, null))));
  }

  private boolean frameRead(Node c, Node el) {
    Scheduler q = s();
    boolean verdictReader = q.observeFlightHook != null && (c.config & CONFIG_VERDICT) != 0;
    boolean lane = q.laneStageHook != null && q.passLane != null;
    if ((!lane || ((c.statusFlags & STATUS_UNINITIALIZED) != 0 && (el.statusFlags & STATUS_PENDING) != 0))
      && !verdictReader && ((c.config & (EFFECT_RENDER << 23)) == 0 || (el.config & CONFIG_INPUTS_PUBLISHED) != 0)) {
      return false;
    }
    Transaction t = q.txOf(el);
    if (((lane ? q.passLane.over != null && Scheduler.resolveTx(q.passLane.over) == t : t == q.flushTransaction) && !verdictReader)
      || bornInto(c, t)) {
      return false;
    }
    q.staleReader(c, t);
    if (lane || verdictReader) {
      q.laneServedHook.accept(c, el);
    }
    return true;
  }

  boolean bornInto(Node c, Transaction t) {
    return ((c.statusFlags & STATUS_UNINITIALIZED) != 0 || (c.config & CONFIG_HELD_FRAME) != 0)
      && (c.config & CONFIG_HELD) != 0 && s().txOf(c) == t;
  }

  Node creatorPass(Owner owner) {
    if (owner != null && owner.root) {
      return owner.parentComputed;
    }
    return owner instanceof Node ? (Node) owner : null;
  }

  int creatorReads(Node p, int born) {
    p = creatorPass(p.parent);
    return p != null && (p.flags & REACTIVE_RECOMPUTING_DEPS) != 0
      ? creatorReads(p, born | p.flags)
      : born & (REACTIVE_JOINED | REACTIVE_STAGED_READ);
  }

  private void parkChildren(Node el) {
    rt.owners.markDisposal(el);
    NodeExtension x = ext(el);
    x.pendingDisposal = el.disposal;
    x.pendingFirstChild = el.firstChild;
    el.disposal = null;
    el.firstChild = null;
  }

  Node createEffectNode(Compute fn, Node.EffectFn effectFn, Node.ErrorFn errorFn, int type, NodeOptions options) {
    Node self = new Node(rt);
    self.config = (type << 23) | (options != null && options.ownedWrite ? CONFIG_OWNED_WRITE : 0)
      | (options != null && options.sync ? CONFIG_SYNC : 0) | (options != null ? options.extraConfig : 0);
    self.equals = null;
    self.name = options != null ? options.name : null;
    self.fn = fn;
    self.parent = context;
    self.flags = REACTIVE_LAZY;
    self.statusFlags = STATUS_UNINITIALIZED;
    self.time = s().clock;
    self.effectFn = effectFn;
    self.errorFn = errorFn;
    self.type = type;
    if (options != null && options.unobserved != null) {
      ext(self).unobserved = options.unobserved;
    }
    setupComputedNode(self, true);
    return self;
  }

  private void setupComputedNode(Node self, boolean lazy) {
    self.prevHeap = self;
    Node parent = context != null && context.root ? context.parentComputed : context instanceof Node ? (Node) context : null;
    if (context != null && (context.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
      throw new IllegalStateException(PRIMITIVE_IN_FORBIDDEN_SCOPE_MESSAGE);
    }
    assertNotInEffectCallback();
    if (context != null) {
      rt.owners.linkChild(context, self);
    }
    if (parent != null) {
      self.height = parent.height + 1;
    }
    if (!lazy) {
      recompute(self, true);
    }
  }

  Node signal(Object v, NodeOptions options) {
    Node n = new Node(rt);
    n.equals = options != null ? options.equals : Node.IS_EQUAL;
    n.name = options != null ? options.name : null;
    n.config = options != null && options.ownedWrite ? CONFIG_OWNED_WRITE : 0;
    n.value = v;
    n.time = s().clock;
    if (options != null && options.unobserved != null) {
      ext(n).unobserved = options.unobserved;
    }
    return n;
  }

  <T> T untrack(Supplier<T> fn) {
    boolean prevTracking = tracking;
    tracking = false;
    try {
      return fn.get();
    } finally {
      tracking = prevTracking;
    }
  }

  <T> T spectate(Supplier<T> fn) {
    boolean prev = spectating;
    spectating = true;
    try {
      return untrack(fn);
    } finally {
      spectating = prev;
    }
  }

  void prepareComputed(Node comp, boolean refresh) {
    if ((comp.flags & REACTIVE_LAZY) != 0) {
      comp.flags &= ~REACTIVE_LAZY;
      recompute(comp, true);
    } else if ((comp.flags & REACTIVE_DISPOSED) != 0) {
      if ((comp.config & CONFIG_AUTO_DISPOSE) != 0) {
        Owner parent = comp.parent;
        if (parent != null) {
          if ((parent.flags & REACTIVE_DISPOSED) != 0) {
            comp.config &= ~CONFIG_AUTO_DISPOSE;
            return;
          }
          if ((comp.flags & REACTIVE_ZOMBIE) == 0) {
            rt.owners.linkChild(parent, comp);
          }
        }
        recompute(comp, true);
      }
    } else if (refresh) {
      updateIfNecessary(comp);
    }
  }

  boolean readerSeesCommitted(Node el, Node c) {
    return c == null || el.pendingValue == NOT_PENDING || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0;
  }

  void markUnflushedStaged() {
    unflushedStaged = true;
  }

  Object unflushedValue(Node el) {
    if (s().running || el.pendingValue == NOT_PENDING || (el.config & CONFIG_PROMOTED) != 0) {
      return NOT_PENDING;
    }
    if ((el.config & CONFIG_HELD) != 0) {
      return el.x.flushedAt == s().clock ? el.x.flushed : NOT_PENDING;
    }
    return el.value;
  }

  void stashFlushed(Node el) {
    NodeExtension x = el.x;
    if (x.flushedAt != s().clock) {
      x.flushed = el.pendingValue;
      x.flushedAt = s().clock;
      unflushedStaged = true;
    }
  }

  boolean markLateLinker(Node c) {
    c.flags |= REACTIVE_MISSED_WAKE;
    return true;
  }

  void resyncUnflushedCompanions() {
    unflushedStaged = false;
    if (!promotedWrites.isEmpty()) {
      for (Node el : promotedWrites) {
        el.config &= ~CONFIG_PROMOTED;
      }
      promotedWrites.clear();
    }
  }

  void pullComputed(Node owner, Node c) {
    Heap heap = s().dirtyQueue;
    if (owner.height >= heap.min) {
      heap.markNode(c, REACTIVE_DIRTY);
      heap.markHeap();
      updateIfNecessary(owner);
    } else if ((c.config & CONFIG_FRESH_READ) != 0) {
      updateIfNecessary(owner);
    }
    int height = owner.height;
    if (height >= c.height && owner.parent != c) {
      c.height = height + 1;
    }
  }

  private boolean servesStaged(Node c, Node el, Node owner) {
    Scheduler q = s();
    if (q.passLane != null || (c.statusFlags & owner.statusFlags & STATUS_UNINITIALIZED) == 0
      || el.pendingValue == NOT_PENDING || (el.config & (CONFIG_HELD | CONFIG_STAGED_ERROR)) != 0 || c.type != 0) {
      return false;
    }
    if ((c.flags & REACTIVE_MISSED_WAKE) != 0) {
      return true;
    }
    if (c.pendingValue == NOT_PENDING && (c.x == null || c.x.served != q.clock)
      && ((c.statusFlags & STATUS_PENDING) == 0 || (c.x != null && c.x.pendingSources != null
        && c.x.pendingSources.remove(((NotReadyError) owner.x.error).source)))) {
      ext(c).served = q.clock;
      c.flags |= REACTIVE_MISSED_WAKE;
      return true;
    }
    return false;
  }

  /** Solid's read(). */
  Object read(Node el) {
    Scheduler q = s();
    Owner ctx = context;
    Node c = ctx != null && ctx.root ? ctx.parentComputed : ctx instanceof Node ? (Node) ctx : null;
    if (verdict != null) {
      return verdict.apply(el, c);
    }
    if (el.fn != null) {
      prepareComputed(el, false);
    }
    if (el.fn == null && (!unflushedStaged || el.pendingValue == NOT_PENDING) && (el.config & CONFIG_OVERRIDE) == 0) {
      if (c != null) {
        if (tracking) {
          rt.graph.link(el, c);
        }
        if ((el.config & CONFIG_HELD) != 0 && (c.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
          if (frameRead(c, el)) {
            return el.value;
          }
          joinPass(c, el);
        }
      }
      if (c == null || el.pendingValue == NOT_PENDING || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
        return el.value;
      }
      boolean netted = el.pendingValue == el.value || java.util.Objects.equals(el.pendingValue, el.value);
      if (netted || (((q.observeFlightHook != null && (c.config & CONFIG_VERDICT) != 0)
        || (q.laneStageHook != null && q.passLane != null && q.passLane.folded != null)) && stagedScreen(c, el))) {
        return el.value;
      }
      stagedRead(c, null);
      return el.pendingValue;
    }
    boolean committed = false;
    if (c != null) {
      if (tracking) {
        rt.graph.link(el, c);
        if (el.fn != null) {
          pullComputed(el, c);
          if (q.observeFlightHook != null && c.type == 0) {
            c.flags |= el.config & CONFIG_VERDICT & ((el.statusFlags & STATUS_PENDING) != 0
              && ((c.config & CONFIG_STAGED) != 0 || (c.statusFlags & STATUS_UNINITIALIZED) != 0) ? c.config : -1);
          }
        }
      }
      if ((el.config & CONFIG_HELD) != 0 && (el.config & CONFIG_OVERRIDE) == 0
        && (c.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
        if ((el.statusFlags & STATUS_UNINITIALIZED) != 0 || !frameRead(c, el)) {
          joinPass(c, el);
        } else {
          committed = true;
        }
      }
    }
    if (q.laneReadHook != null && (el.config & CONFIG_OVERRIDE) != 0) {
      Object v = q.laneReadHook.read(c, el);
      if (v != NOT_PENDING) {
        return v;
      }
    }
    if (q.laneStageHook != null && (el.statusFlags & STATUS_PENDING) != 0 && !committed
      && (el.config & CONFIG_OVERRIDE) == 0 && (el.statusFlags & STATUS_UNINITIALIZED) == 0) {
      if (q.passLane != null && !(c != null && (c.statusFlags & STATUS_UNINITIALIZED) != 0)) {
        committed = true;
      } else if (c != null && (c.config & CONFIG_VERDICT) != 0) {
        committed = q.observeFlightHook.test(c, el);
      }
    }
    if ((el.statusFlags & STATUS_PENDING) != 0 && !committed) {
      if (c != null) {
        if ((c.config & CONFIG_CHILDREN_FORBIDDEN) == 0 || (el.statusFlags & STATUS_UNINITIALIZED) != 0) {
          if (q.observeFlightHook != null && servesStaged(c, el, el)) {
            return el.pendingValue;
          }
          if (!tracking && !spectating && el != c) {
            rt.graph.link(el, c);
          }
          throw Errors.<RuntimeException>sneak(el.x.error);
        }
      } else if ((el.statusFlags & STATUS_UNINITIALIZED) != 0) {
        throw Errors.<RuntimeException>sneak(el.x.error);
      }
    }
    if (el.fn != null && (el.statusFlags & STATUS_ERROR) != 0) {
      if (tracking && el.time < q.clock) {
        recompute(el, false);
        return read(el);
      }
      if (!committed && (el.config & CONFIG_STAGED_ERROR) != 0) {
        serve(el, c);
      }
      throw Errors.<RuntimeException>sneak(el.x.error);
    }
    if (committed || c == null) {
      throwShown(el);
    }
    Object value = serve(el, committed ? null : c);
    if (c == null && el.fn != null && (el.config & CONFIG_AUTO_DISPOSE) != 0 && (el.statusFlags & STATUS_PENDING) == 0
      && el.subs == null) {
      rt.graph.dormantNodes.add(el);
      q.schedule();
    }
    return value;
  }

  private void throwShown(Node el) {
    if (el.x != null && el.x.shownError != null) {
      throw Errors.<RuntimeException>sneak(el.x.shownError);
    }
  }

  Object serve(Node el, Node c) {
    Scheduler q = s();
    if (el.pendingValue != NOT_PENDING && (el.statusFlags & STATUS_UNINITIALIZED) != 0
      && (c == null || spectating || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0)) {
      throw new NotReadyError(null);
    }
    Object u = c != null && unflushedStaged ? unflushedValue(el) : NOT_PENDING;
    if (u != NOT_PENDING) {
      markLateLinker(c);
      return u;
    }
    if (readerSeesCommitted(el, c)) {
      return el.value;
    }
    if (q.observeFlightHook != null && (c.config & CONFIG_VERDICT) != 0 && (el.statusFlags & STATUS_UNINITIALIZED) == 0
      && stagedScreen(c, el)) {
      throwShown(el);
      return el.value;
    }
    stagedRead(c, el);
    if ((el.config & CONFIG_STAGED_ERROR) != 0) {
      throw Errors.<RuntimeException>sneak((Throwable) el.pendingValue);
    }
    return el.pendingValue;
  }

  void stagedRead(Node c, Node el) {
    if (el == null || (el.statusFlags & STATUS_UNINITIALIZED) == 0) {
      c.flags |= REACTIVE_STAGED_READ;
      Scheduler q = s();
      if (q.laneSeamsHook != null && (q.passLane != null || (c.type != 0 && (c.config & CONFIG_VERDICT) != 0))) {
        q.stagedReaders.add(c);
      }
    }
  }

  boolean stagedScreen(Node c, Node el) {
    Scheduler q = s();
    if (q.flushTransaction == null || bornInto(c, q.flushTransaction)
      || (el != null && (el.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD)) {
      return false;
    }
    if (q.observeFlightHook != null && q.flushTransaction == q.latestOpened && q.passLane == null
      && (c.config & CONFIG_VERDICT) != 0 && (c.statusFlags & STATUS_UNINITIALIZED) == 0) {
      q.stagedReaders.add(c);
      return false;
    }
    q.staleReader(c, q.flushTransaction);
    return true;
  }

  void notePromotedWrite(Node el) {
    if (s().running || (el.config & CONFIG_PROMOTED) != 0) {
      return;
    }
    el.config |= CONFIG_PROMOTED;
    promotedWrites.add(el);
  }

  boolean isSame(Node el, Object a, Object b) {
    if (el.equals == Node.IS_EQUAL) {
      return java.util.Objects.equals(a, b);
    }
    try {
      return el.equals != null && el.equals.test(a, b);
    } catch (NotReadyError e) {
      return false;
    } catch (RuntimeException e) {
      rt.async.notifyStatus(el, STATUS_ERROR, e);
      return true;
    }
  }

  /** Solid's setSignal(el, v). */
  Object setSignal(Node el, Object v) {
    return write(el, v, null);
  }

  /** Solid's setSignal(el, prev => next). */
  Object updateSignal(Node el, UnaryOperator<Object> fn) {
    return write(el, null, fn);
  }

  private Object write(Node el, Object v, UnaryOperator<Object> fn) {
    Scheduler q = s();
    if ((el.config & CONFIG_OWNED_WRITE) == 0 && !(context != null && (context.config & CONFIG_CHILDREN_FORBIDDEN) != 0)
      && context != null && disposalDepth == 0) {
      throw new IllegalStateException(REACTIVE_WRITE_IN_OWNED_SCOPE_SIGNAL_MESSAGE);
    }
    Object currentValue = el.pendingValue == NOT_PENDING ? el.value : el.pendingValue;
    if (fn != null) {
      v = fn.apply(currentValue);
    }
    if (q.laneWriteHook != null && (el.config & CONFIG_OVERRIDE) != 0) {
      return q.laneWriteHook.apply(el, v);
    }
    if ((el.config & CONFIG_HELD) != 0) {
      q.joinFuture(q.txOf(el));
    }
    boolean hasCustom = q.observeFlightHook != null;
    boolean custom = hasCustom && el.equals != Node.IS_EQUAL;
    boolean valueChanged = (el.statusFlags & STATUS_UNINITIALIZED) != 0 || (el.x != null && el.x.shownError != null)
      || (hasCustom && !custom ? !java.util.Objects.equals(currentValue, v) : !isSame(el, currentValue, v));
    if (!valueChanged) {
      return v;
    }
    boolean wasStaged = el.pendingValue != NOT_PENDING;
    if (!wasStaged) {
      q.queuePendingNode(el);
    } else if ((el.config & CONFIG_HELD) != 0 && !q.running) {
      stashFlushed(el);
    }
    el.pendingValue = custom && wasStaged
      && !((el.statusFlags & STATUS_UNINITIALIZED) != 0 || (el.x != null && el.x.shownError != null))
      && isSame(el, el.value, v) ? el.value : v;
    if (context != null) {
      notePromotedWrite(el);
    }
    if (el.fn != null) {
      el.time = q.clock;
    }
    if (wasStaged && el.notifiedAt == q.notifyEpoch) {
      return v;
    }
    q.insertSubs(el);
    q.schedule();
    return v;
  }

  /** Async.ts's setSignal(el, () => value): a landing, which no owned-scope check applies to. */
  void setSignalFromLanding(Node el, Object value) {
    Owner previous = context;
    context = null;
    try {
      write(el, value, null);
    } finally {
      context = previous;
    }
  }

  /** Solid's setMemo: a write to a writable derived signal. */
  Object setMemo(Node el, Object v, UnaryOperator<Object> fn) {
    Scheduler q = s();
    boolean held = (el.config & CONFIG_HELD) != 0 && (el.flags & REACTIVE_MANUAL_WRITE) == 0 && q.txOf(el) != q.flushTransaction;
    if (held && fn != null) {
      v = fn.apply(el.value);
      fn = null;
    }
    if ((el.statusFlags & STATUS_ERROR) != 0) {
      el.x.shownError = el.x.error;
      rt.async.notifyStatus(el, STATUS_NONE, null);
    }
    v = write(el, v, fn);
    el.config &= ~CONFIG_STAGED_ERROR;
    if (held) {
      el.flags = (el.flags & ~REACTIVE_CHECK) | REACTIVE_DIRTY;
      q.dirtyQueue.insertIntoHeap(el);
      q.schedule();
    } else if (el.pendingValue != NOT_PENDING) {
      el.flags |= REACTIVE_MANUAL_WRITE;
    }
    return v;
  }

  <T> T runWithOwner(Owner owner, Supplier<T> fn) {
    Owner oldContext = context;
    boolean prevTracking = tracking;
    context = owner;
    tracking = false;
    try {
      return fn.get();
    } finally {
      context = oldContext;
      tracking = prevTracking;
    }
  }

  <T> T staleValues(Supplier<T> fn, boolean set) {
    boolean prevStale = stale;
    stale = set;
    try {
      return fn.get();
    } finally {
      stale = prevStale;
    }
  }

  void markRefresh(Node node) {
    if (context != null && (node.config & CONFIG_OWNED_WRITE) == 0 && (context.config & CONFIG_CHILDREN_FORBIDDEN) == 0
      && disposalDepth == 0) {
      throw new IllegalStateException(REACTIVE_WRITE_IN_OWNED_SCOPE_REFRESH_MESSAGE);
    }
    if (node.fn != null && (node.flags & REACTIVE_DISPOSED) == 0) {
      node.flags = (node.flags & ~REACTIVE_CHECK) | REACTIVE_DIRTY
        | ((node.flags & (REACTIVE_DIRTY | REACTIVE_CHECK | REACTIVE_IN_HEAP)) != 0 ? 0 : REACTIVE_REASK);
      s().dirtyQueue.insertIntoHeap(node);
      s().schedule();
    }
  }
}
