package j2act.internal.signals;

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.StatusError;
import j2act.internal.signals.Node.Link;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * Port of @solidjs/signals core/async.ts at 9449845. A flight is a CompletionStage or an AsyncIterable. JS
 * promises always land a tick later; a Java future can land on another thread, so a landing is posted to the
 * runtime's lane, and one already complete when asked is read synchronously, as Solid does for a sync thenable.
 */
final class Async {

  /** Solid's AsyncIterable. */
  public interface AsyncIterable<T> {
    AsyncIterator<T> iterator();
  }

  /** Solid's AsyncIterator: a step, and a close that is its return(). */
  public interface AsyncIterator<T> {
    CompletionStage<Step<T>> next();

    void close();
  }

  /** Solid's IteratorResult. */
  public static final class Step<T> {
    final boolean done;
    final T value;

    private Step(boolean done, T value) {
      this.done = done;
      this.value = value;
    }

    public static <T> Step<T> of(T value) {
      return new Step<>(false, value);
    }

    public static <T> Step<T> end() {
      return new Step<>(true, null);
    }
  }

  private final Runtime rt;

  Async(Runtime rt) {
    this.rt = rt;
  }

  static boolean isAsync(Object value) {
    return value instanceof CompletionStage || value instanceof AsyncIterable;
  }

  static boolean isThenable(Object value) {
    return value instanceof CompletionStage;
  }

  /** What `parked.then()` gives: a new stage settling with the one it is given. */
  @SuppressWarnings("unchecked")
  static Object continuation(Object parked) {
    return ((CompletionStage<Object>) parked).thenApply(v -> v);
  }

  /** Solid's `.then(fn, fn)`: at once when it is already settled here, otherwise on the lane. */
  <V> void whenSettled(CompletionStage<V> stage, Runnable fn) {
    whenResult(stage, (v, e) -> fn.run());
  }

  /** Solid's `.then(onValue, onError)`, with both as one callback. */
  <V> void whenResult(CompletionStage<V> stage, BiConsumer<V, Throwable> fn) {
    Thread me = Thread.currentThread();
    boolean[] sync = {true};
    stage.whenComplete((v, e) -> {
      Throwable cause = e == null ? null : unwrap(e);
      if (sync[0] && Thread.currentThread() == me) {
        fn.accept(v, cause);
      } else {
        rt.post(() -> fn.accept(v, cause));
      }
    });
    sync[0] = false;
  }

  boolean addPendingSource(Node el, Node source) {
    if (el.x != null && el.x.pendingSources != null && el.x.pendingSources.contains(source)) {
      return false;
    }
    NodeExtension x = rt.core.ext(el);
    if (x.pendingSources == null) {
      x.pendingSources = new LinkedHashSet<>();
    }
    x.pendingSources.add(source);
    return true;
  }

  boolean removePendingSource(Node el, Node source) {
    Set<Node> sources = el.x != null ? el.x.pendingSources : null;
    if (sources == null || !sources.remove(source)) {
      return false;
    }
    if (sources.isEmpty()) {
      el.x.pendingSources = null;
    }
    return true;
  }

  void clearInFlight(Node el) {
    if (el.x != null) {
      el.x.inFlight = null;
    }
  }

  private void clearPendingSources(Node el) {
    if (el.x != null) {
      el.x.pendingSources = null;
    }
  }

  boolean retryReaches(Node el, Node source) {
    for (Link d = el.deps; d != null; d = d.nextDep) {
      Node dep = d.dep;
      if (dep == source || (dep.x != null && dep.x.pendingSources != null && dep.x.pendingSources.contains(source))) {
        return true;
      }
    }
    return false;
  }

  void parkLoadingWindow(Node el, NotReadyError e) {
    rt.core.ext(el).blocked = true;
    if (e.source != null) {
      addPendingSource(el, e.source);
    }
    if ((el.statusFlags & STATUS_ERROR) == 0) {
      setPendingError(el, e.source, e);
    }
  }

  void setPendingError(Node el, Node source, Throwable error) {
    if (source == null) {
      if (el.x != null) {
        el.x.error = null;
      }
      return;
    }
    Throwable current = error instanceof NotReadyError && ((NotReadyError) error).source == source ? error
      : el.x != null ? el.x.error : null;
    rt.core.ext(el).error = current instanceof NotReadyError && ((NotReadyError) current).source == source
      ? current : new NotReadyError(source);
  }

  private interface Visit {
    boolean visit(Node node, boolean seen);
  }

  private void walkDependents(Node el, Visit fn) {
    Set<Node> visited = new HashSet<>();
    walk(el, fn, visited);
  }

  private void walk(Node el, Visit fn, Set<Node> visited) {
    for (Link s = el.subs; s != null; s = s.nextSub) {
      Node node = s.sub;
      boolean seen = visited.contains(node);
      if (fn.visit(node, seen) && !seen) {
        visited.add(node);
        walk(node, fn, visited);
      }
    }
  }

  void releaseIfSettledUnobserved(Node node) {
    if (node.fn != null && (node.config & CONFIG_AUTO_DISPOSE) != 0 && node.subs == null
      && (node.flags & REACTIVE_ZOMBIE) == 0 && (node.statusFlags & STATUS_PENDING) == 0) {
      if ((node.flags & REACTIVE_IN_HEAP) != 0) {
        node.flags |= REACTIVE_RELEASE_AFTER;
      } else {
        rt.graph.unobserved(node);
      }
    }
  }

  void releaseSettledDependents(Node el) {
    List<Node> candidates = new ArrayList<>();
    walkDependents(el, (node, seen) -> {
      if (!seen && node.subs == null && (node.config & CONFIG_AUTO_DISPOSE) != 0) {
        candidates.add(node);
      }
      return true;
    });
    for (Node node : candidates) {
      releaseIfSettledUnobserved(node);
    }
  }

  boolean stageError(Node el, Throwable error, boolean inPass) {
    if (!((el.config & CONFIG_HELD) != 0 || (inPass && (el.flags & REACTIVE_STAGED_READ) != 0 && rt.scheduler.running))
      || rt.scheduler.passLane != null) {
      return false;
    }
    clearPendingSources(el);
    if (el.x != null) {
      el.x.blocked = false;
    }
    el.statusFlags &= STATUS_ERROR | STATUS_UNINITIALIZED;
    if ((el.statusFlags & STATUS_ERROR) == 0) {
      setPendingError(el, null, null);
    }
    el.pendingValue = error instanceof StatusError ? error : new StatusError(el, error);
    el.config |= CONFIG_STAGED | CONFIG_STAGED_ERROR;
    settlePendingSource(el, el);
    rt.scheduler.schedule();
    return true;
  }

  void settleErroredDependents(Node el, Throwable error) {
    boolean[] scheduled = new boolean[1];
    walkDependents(el, (node, seen) -> {
      if (!seen && node.x != null && node.x.error == error) {
        rt.scheduler.dirtyQueue.enqueueSub(node);
        scheduled[0] = true;
      }
      return true;
    });
    if (scheduled[0]) {
      rt.scheduler.schedule();
    }
  }

  void settlePendingSource(Node el, Node source) {
    Scheduler q = rt.scheduler;
    removePendingSource(el, source);
    boolean[] scheduled = new boolean[1];
    List<Node> released = new ArrayList<>();
    walkDependents(el, (node, seen) -> {
      if (seen || (source != el && retryReaches(node, source)) || !removePendingSource(node, source)) {
        if (q.holdWorkHook != null) {
          if ((node.config & CONFIG_VERDICT) != 0) {
            q.dirtyQueue.enqueueSub(node);
          } else {
            q.stageMarkHook.accept(node, el);
          }
        }
        return false;
      }
      node.time = q.clock;
      Node remaining = node.x != null && node.x.pendingSources != null && !node.x.pendingSources.isEmpty()
        ? node.x.pendingSources.iterator().next() : null;
      boolean errored = (node.statusFlags & STATUS_ERROR) != 0;
      if (remaining != null) {
        if (!errored) {
          setPendingError(node, remaining, null);
        }
      } else {
        node.statusFlags &= ~STATUS_PENDING;
        if (!errored) {
          setPendingError(node, null, null);
        }
        if ((node.config & CONFIG_HELD) != 0) {
          q.joinFuture(q.txOf(node));
        }
        if (node.x != null && node.x.blocked) {
          q.dirtyQueue.enqueueSub(node);
          scheduled[0] = true;
        }
        if (node.x != null) {
          node.x.blocked = false;
        }
        if (node.subs == null && (node.config & CONFIG_AUTO_DISPOSE) != 0) {
          released.add(node);
        }
        if (q.stageMarkHook != null) {
          q.stageMarkHook.accept(node, el);
        }
      }
      return true;
    });
    for (Node node : released) {
      releaseIfSettledUnobserved(node);
    }
    if (scheduled[0]) {
      q.schedule();
    }
  }

  void releaseFlightTeardown(Node el) {
    Runnable teardown = el.x != null ? el.x.flightTeardown : null;
    if (teardown != null) {
      el.x.flightTeardown = null;
      teardown.run();
    }
  }

  void onSupersede(Runnable fn) {
    Owner ctx = rt.core.context;
    if (!(ctx instanceof Node) || ((Node) ctx).fn == null || (ctx.flags & REACTIVE_RECOMPUTING_DEPS) == 0) {
      return;
    }
    NodeExtension x = rt.core.ext(ctx);
    Runnable prev = x.flightTeardown;
    x.flightTeardown = prev == null ? fn : () -> {
      try {
        prev.run();
      } finally {
        fn.run();
      }
    };
  }

  private static Throwable unwrap(Throwable t) {
    while (t instanceof CompletionException && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  /** One handleAsync call's closures. */
  private final class Flight {
    final Node el;
    final Object result;
    final java.util.function.Consumer<Object> setter;
    final BiConsumer<Throwable, Boolean> onError;
    Object syncValue;
    /** Solid's liveLanded: null until a flight's landing is known. */
    Boolean liveLanded;

    Flight(Node el, Object result, java.util.function.Consumer<Object> setter, BiConsumer<Throwable, Boolean> onError) {
      this.el = el;
      this.result = result;
      this.setter = setter;
      this.onError = onError;
    }

    boolean current() {
      return el.x != null && el.x.inFlight == result;
    }

    void handleError(Throwable error) {
      if (!current()) {
        return;
      }
      boolean stillPending = error instanceof NotReadyError;
      if (stillPending && !retryReaches(el, ((NotReadyError) error).source)) {
        stillPending = false;
        error = new IllegalStateException("Read of an unresolved async source after the compute returned. Reads only register"
          + " as dependencies in the compute itself; a source first read off the lane cannot retry when it settles.");
      }
      if (stillPending && el.loading) {
        clearInFlight(el);
        parkLoadingWindow(el, (NotReadyError) error);
        el.time = rt.scheduler.clock;
        return;
      }
      if (stillPending || !stageError(el, error, false)) {
        notifyStatus(el, stillPending ? STATUS_PENDING : STATUS_ERROR, error);
        if (!stillPending && el.x != null && (el.x.pendingFirstChild != null || el.x.pendingDisposal != null)) {
          rt.owners.disposeChildren(el, false, true);
        }
      }
      if (stillPending) {
        settlePendingSource(el, el);
      }
      el.time = rt.scheduler.clock;
      if (!stillPending) {
        releaseSettledDependents(el);
      }
      if (onError != null) {
        onError.accept(error, stillPending);
      }
    }

    void asyncWrite(Object value, Runnable then) {
      if (!current()) {
        return;
      }
      Scheduler q = rt.scheduler;
      if ((el.flags & REACTIVE_DIRTY) != 0 && !(q.holdWorkHook != null && (el.statusFlags & STATUS_UNINITIALIZED) != 0)) {
        return;
      }
      boolean carried = false;
      if (q.holdWorkHook != null) {
        for (Node n : q.pendingNodes) {
          carried |= n.pendingValue != NOT_PENDING;
        }
      }
      boolean wasUninitialized = (el.statusFlags & STATUS_UNINITIALIZED) != 0;
      landStatus(el, false);
      if (setter != null) {
        try {
          setter.accept(value);
        } catch (RuntimeException | Error error) {
          handleError(error);
          return;
        }
        if ((el.config & CONFIG_HELD) == 0) {
          el.x.shownError = null;
          el.config &= ~CONFIG_INPUTS_PUBLISHED;
        }
        if (wasUninitialized) {
          landStatus(el, true);
        }
      } else {
        rt.core.setSignalFromLanding(el, value);
      }
      if (el.pendingValue == NOT_PENDING) {
        el.loading = false;
        rt.graph.trimStaleDeps(el);
      }
      settlePendingSource(el, el);
      q.schedule();
      if (then != null || !carried) {
        q.flush();
      }
      if (then != null) {
        then.run();
      }
    }

    boolean settleAutodispose() {
      if ((el.config & CONFIG_AUTO_DISPOSE) != 0 && el.subs == null && (el.statusFlags & STATUS_PENDING) == 0) {
        rt.graph.unobserved(el);
        return true;
      }
      return false;
    }

    /** Runs a landing on the lane, or now if it was already done when asked. */
    <V> boolean whenDone(CompletionStage<V> stage, BiConsumer<V, Throwable> onSync, BiConsumer<V, Throwable> onLater) {
      Thread me = Thread.currentThread();
      boolean[] sync = {true};
      boolean[] calledSync = new boolean[1];
      stage.whenComplete((v, e) -> {
        Throwable cause = e == null ? null : unwrap(e);
        if (sync[0] && Thread.currentThread() == me) {
          calledSync[0] = true;
          onSync.accept(v, cause);
        } else {
          rt.post(() -> onLater.accept(v, cause));
        }
      });
      sync[0] = false;
      return calledSync[0];
    }

    boolean consumeIterator(AsyncIterable<Object> source, Owner owner) {
      AsyncIterator<Object> it = source.iterator();
      boolean[] hadValue = new boolean[1];
      boolean[] completed = new boolean[1];
      boolean[] initialRead = {owner == null};
      Runnable close = () -> {
        if (completed[0]) {
          return;
        }
        completed[0] = true;
        try {
          it.close();
        } catch (RuntimeException e) {
          // closing what nobody reads any more
        }
      };
      rt.owners.cleanup(close, owner != null ? owner : rt.core.context);
      rt.core.ext(el).flightTeardown = close;
      boolean immediatelyDone = iterate(it, hadValue, completed, initialRead);
      initialRead[0] = false;
      return hadValue[0] || immediatelyDone;
    }

    private void iterateOrRelease(AsyncIterator<Object> it, boolean[] hadValue, boolean[] completed, boolean[] initialRead) {
      try {
        if (!settleAutodispose()) {
          iterate(it, hadValue, completed, initialRead);
        }
      } catch (RuntimeException | Error e) {
        handleError(e);
      }
    }

    private boolean iterate(AsyncIterator<Object> it, boolean[] hadValue, boolean[] completed, boolean[] initialRead) {
      Object[] syncResult = new Object[1];
      Throwable[] syncError = new Throwable[1];
      boolean[] resolved = new boolean[1];
      boolean[] rejected = new boolean[1];
      whenDone(it.next(), (r, e) -> {
        if (initialRead[0]) {
          if (e != null) {
            syncError[0] = e;
            rejected[0] = true;
          } else {
            syncResult[0] = r;
            resolved[0] = true;
            if (r.done) {
              completed[0] = true;
            }
          }
        } else {
          later(it, r, e, hadValue, completed, initialRead);
        }
      }, (r, e) -> later(it, r, e, hadValue, completed, initialRead));
      if (rejected[0]) {
        completed[0] = true;
        handleError(syncError[0]);
        if (initialRead[0]) {
          throw Errors.<RuntimeException>sneak(syncError[0]);
        }
        return true;
      }
      if (resolved[0] && !((Step<?>) syncResult[0]).done) {
        syncValue = ((Step<?>) syncResult[0]).value;
        hadValue[0] = true;
        return iterate(it, hadValue, completed, initialRead);
      }
      return resolved[0] && ((Step<?>) syncResult[0]).done;
    }

    private void later(AsyncIterator<Object> it, Step<Object> r, Throwable e, boolean[] hadValue, boolean[] completed,
                       boolean[] initialRead) {
      if (e != null) {
        if (current()) {
          completed[0] = true;
          handleError(e);
          settleAutodispose();
        }
        return;
      }
      if (!current()) {
        return;
      }
      if (!r.done) {
        hadValue[0] = true;
        asyncWrite(r.value, () -> iterateOrRelease(it, hadValue, completed, initialRead));
      } else {
        completed[0] = true;
        if (hadValue[0]) {
          rt.scheduler.schedule();
          rt.scheduler.flush();
        } else {
          asyncWrite(null, null);
        }
        settleAutodispose();
      }
    }

    @SuppressWarnings("unchecked")
    boolean flattenIfIterable(Object value, Owner owner) {
      if (!(value instanceof AsyncIterable)) {
        return false;
      }
      syncValue = null;
      boolean landed = consumeIterator((AsyncIterable<Object>) value, owner);
      if (owner == null) {
        liveLanded = landed;
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    Object run() {
      Scheduler q = rt.scheduler;
      rt.core.ext(el).inFlight = result;
      if (q.holdWorkHook != null && (el.flags & REACTIVE_WINDOW) == 0) {
        el.config |= CONFIG_ASYNC;
      }
      el.x.q = q.question != 0 ? q.question : Scheduler.MAINLINE_QUESTION;
      el.x.pendingSources = null;
      if (result instanceof CompletionStage) {
        Object[] value = new Object[1];
        Throwable[] syncError = new Throwable[1];
        boolean[] resolved = new boolean[1];
        boolean[] rejected = new boolean[1];
        whenDone((CompletionStage<Object>) result, (v, e) -> {
          if (e != null) {
            syncError[0] = e;
            rejected[0] = true;
          } else {
            value[0] = v;
            resolved[0] = true;
          }
        }, (v, e) -> {
          if (e != null) {
            handleError(e);
            settleAutodispose();
            return;
          }
          try {
            if (current() && (el.flags & REACTIVE_DISPOSED) == 0 && flattenIfIterable(v, el)) {
              return;
            }
            asyncWrite(v, null);
          } catch (RuntimeException | Error error) {
            handleError(error);
          }
          settleAutodispose();
        });
        if (rejected[0]) {
          handleError(syncError[0]);
          throw Errors.<RuntimeException>sneak(syncError[0]);
        } else if (!resolved[0]) {
          liveLanded = false;
        } else {
          syncValue = value[0];
          if (!flattenIfIterable(syncValue, null)) {
            el.loading = false;
          }
        }
      } else {
        flattenIfIterable(result, null);
      }
      if (liveLanded != null) {
        if (!liveLanded) {
          if (el.loading) {
            return el.value;
          }
          throw new NotReadyError(rt.core.context instanceof Node ? (Node) rt.core.context : el);
        }
        el.loading = false;
      }
      return syncValue;
    }
  }

  Object handleAsync(Node el, Object result, java.util.function.Consumer<Object> setter, BiConsumer<Throwable, Boolean> onError) {
    if ((el.config & CONFIG_SYNC) != 0) {
      throw new IllegalStateException("[SYNC_NODE_RECEIVED_ASYNC] A computed or effect created as sync returned a future.");
    }
    return new Flight(el, result, setter, onError).run();
  }

  void clearStatus(Node el, boolean clearUninitialized) {
    if (el.x != null && el.x.pendingSources != null) {
      clearPendingSources(el);
    }
    if (el.x != null && el.x.blocked) {
      el.x.blocked = false;
    }
    el.statusFlags = clearUninitialized ? 0 : el.statusFlags & STATUS_UNINITIALIZED;
    if (el.x != null && el.x.error != null) {
      setPendingError(el, null, null);
    }
    if (el.type != 0 && rt.core.effectStatusNotify != null) {
      rt.core.effectStatusNotify.accept(el);
    }
  }

  private void landStatus(Node el, boolean clearUninitialized) {
    Set<Node> sources = el.x != null ? el.x.pendingSources : null;
    if (sources != null) {
      sources.remove(el);
    }
    if (sources != null && !sources.isEmpty()) {
      el.x.blocked = false;
      if (clearUninitialized) {
        el.statusFlags = STATUS_PENDING;
      }
      setPendingError(el, sources.iterator().next(), null);
    } else {
      clearStatus(el, clearUninitialized);
    }
  }

  void notifyStatus(Node el, int status, Throwable error) {
    if (status == STATUS_ERROR && !(error instanceof StatusError) && !(error instanceof NotReadyError)) {
      error = new StatusError(el, error);
    }
    Node pendingSource = status == STATUS_PENDING && error instanceof NotReadyError ? ((NotReadyError) error).source : null;
    if (pendingSource != null) {
      if ((el.statusFlags & STATUS_ERROR) != 0) {
        el.x.shownError = el.x.error;
      }
      addPendingSource(el, pendingSource);
    } else {
      clearPendingSources(el);
    }
    el.statusFlags = status | (status == STATUS_ERROR
      ? ((el.statusFlags & (STATUS_UNINITIALIZED | STATUS_NO_VALUE)) != 0 ? STATUS_NO_VALUE : 0)
      : status == STATUS_PENDING && (el.statusFlags & STATUS_NO_VALUE) != 0 ? STATUS_UNINITIALIZED
        : el.statusFlags & STATUS_UNINITIALIZED);
    if (pendingSource != null) {
      setPendingError(el, pendingSource, error);
    } else {
      rt.core.ext(el).error = error;
    }
    Scheduler q = rt.scheduler;
    if (q.guessing && status == STATUS_PENDING && (el.config & CONFIG_GUESS) != 0) {
      if (q.passLane == null) {
        q.joinFuture((el.flags & REACTIVE_REASK) != 0 ? null : q.txOf(el).parent);
      }
      return;
    }
    if (el.type != 0 && rt.core.effectStatusNotify != null) {
      rt.core.effectStatusNotify.accept(el);
      return;
    }
    propagateStatus(el, status, error);
  }

  void propagateStatus(Node el, int status, Throwable error) {
    Scheduler q = rt.scheduler;
    Node pendingSource = status == STATUS_PENDING && error instanceof NotReadyError ? ((NotReadyError) error).source : null;
    for (Link link = el.subs; link != null; link = link.nextSub) {
      Node sub = link.sub;
      sub.time = q.clock;
      if ((sub.config & CONFIG_REDERIVE) != 0
        || (q.holdWorkHook != null && ((sub.config & CONFIG_VERDICT) != 0 || (pendingSource != null && (sub.config & CONFIG_STAGED_ERROR) != 0))
          && !(pendingSource != null && q.holdWorkHook.test(sub, el)))
        || (status == STATUS_PENDING && link.gen != sub.depGen)) {
        if (status != STATUS_PENDING) {
          removePendingSource(sub, el);
        }
        q.dirtyQueue.enqueueSub(sub);
        q.schedule();
        continue;
      }
      if (pendingSource != null && sub.x != null && sub.x.pendingSources != null && sub.x.pendingSources.contains(pendingSource)) {
        if (q.passLane == null && (sub.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD && q.running
          && !(q.freshHook != null && Signals.truthy(q.freshHook.fresh(sub, pendingSource.time == q.clock)))) {
          q.joinFuture(q.txOf(sub));
        }
        continue;
      }
      if (pendingSource != null || (status != STATUS_PENDING && (sub.x == null || sub.x.error != error || sub.x.pendingSources != null))) {
        if (q.laneStageHook != null && q.passLane != null) {
          q.laneStageHook.stage(sub, q.passLane, true, el);
        } else {
          q.queuePendingNode(sub);
          if (status == STATUS_PENDING && (sub.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD && sub.type == 0) {
            q.joinFuture(q.txOf(sub));
          }
        }
        notifyStatus(sub, status, error);
      }
    }
  }
}
