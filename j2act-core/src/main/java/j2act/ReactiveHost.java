package j2act;

import j2act.internal.signals.Runtime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * One tab's graph (ADR 0037): one lane, one graph and one scheduler, as Solid has one of each per page; or a store
 * hub's, which holds a browser's stores and no page. A graph reads another's values through mirrors, and other graphs
 * read its own through what it publishes. The engine's microtask is the end of the lane job
 * that scheduled it, so a job is what JS's synchronous code is, and every job ends with a flush.
 */
final class ReactiveHost {

  private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

  final J2Act engine;
  final Lane lane;
  final Runtime rt;
  /** The one tab of this graph (ADR 0037): a page, or a hub that holds stores and no page. */
  volatile Tab tab;
  /** Its place in the order lanes may wait for each other: a graph of stores waits only for younger ones. */
  final long seq = SEQUENCE.incrementAndGet();
  /** The values of other graphs this one reads. */
  final Mirrors mirrors = new Mirrors(this);
  /** What other graphs read of this one's values, made with the first. */
  private Published published;
  /** Writes to other graphs' values made in this job, sent as one batch each when it ends. Lane-only. */
  private List<Outgoing> outgoing = new ArrayList<>();
  /** What the engine scheduled for the end of this job. Lane-only. */
  private final Deque<Runnable> microtasks = new ArrayDeque<>();
  /** Every tab on this lane, hub included. */
  final Set<Tab> tabs = ConcurrentHashMap.newKeySet();
  private boolean flushing;
  /** The stores of this lane's runtime (ADR 0035), made with the first one. Lane-only. */
  private j2act.internal.store.JavaShape stores;
  /** The tab each store belongs to, by its root target. */
  final java.util.Map<j2act.internal.signals.StoreTarget, Tab> storeTabs =
    java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
  private final java.util.Queue<Runnable> foreignWrites = new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final java.util.concurrent.atomic.AtomicBoolean foreignWriteQueued = new java.util.concurrent.atomic.AtomicBoolean();

  ReactiveHost(J2Act engine) {
    this.engine = engine;
    this.lane = new Lane(engine.executor);
    this.rt = new Runtime(this::postLanding, microtasks::add);
    rt.setErrorLog((message, cause) -> engine.log(System.Logger.Level.WARNING,
      message != null ? message : "a reactive computation failed", cause));
    rt.setUncaught((node, cause) -> {
      engine.log(System.Logger.Level.WARNING, "a load failed with no errored(...) around its read", cause);
      return true;
    });
    rt.setTimer((task, millis) -> {
      java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      engine.later(this, millis, () -> {
        if (!cancelled.get()) {
          task.run();
        }
      });
      return () -> cancelled.set(true);
    });
  }

  /**
   * This runtime's stores. What a store hands out can be read anywhere, so each read finds the lane: at once
   * on it, in an action's transaction from its body, and by a hop from any other thread, untracked.
   */
  j2act.internal.store.JavaShape stores() {
    if (stores == null) {
      stores = new j2act.internal.store.JavaShape(rt);
      stores.reads(mirrors::storeRead);
      stores.opaque(value -> engine.storeOpaque(value.getClass()));
      if (engine.devMode()) {
        stores.checkMutations(message -> engine.log(System.Logger.Level.WARNING, message, null));
      }
      stores.gate(new j2act.internal.store.JavaShape.Gate() {
        @Override public <T> T call(j2act.internal.signals.StoreTarget target, java.util.function.Supplier<T> op) {
          if (lane.isCurrent()) {
            return op.get();
          }
          Tab tab = storeTabs.get(target.root());
          Action.Call call = Action.Call.current();
          if (call != null && tab != null) {
            return call.enter(tab, op);
          }
          Object[] out = new Object[1];
          RuntimeException[] failure = new RuntimeException[1];
          hop(() -> {
            try {
              out[0] = rt.untrack(op);
            } catch (RuntimeException e) {
              failure[0] = e;
            }
          });
          if (failure[0] != null) {
            throw failure[0];
          }
          @SuppressWarnings("unchecked")
          T value = (T) out[0];
          return value;
        }
      });
    }
    return stores;
  }

  /** A landing from another thread: a job of its own, ending with a flush. */
  private void postLanding(Runnable landing) {
    if (lane.isCurrent()) {
      microtasks.add(landing);
      return;
    }
    job(landing);
  }

  /** Queues a job on the lane. */
  void job(Runnable body) {
    lane.execute(() -> {
      try {
        body.run();
      } catch (RuntimeException | Error e) {
        engine.log(System.Logger.Level.ERROR, "lane task failed", e);
      }
      endJob();
    });
  }

  /**
   * Runs body on the lane from the calling thread, then flushes (ADR 0033): what a loader, a foreign thread
   * or an action's body uses instead of posting and waiting, which a one-thread pool could never finish.
   */
  void hop(Runnable body) {
    if (lane.isCurrent()) {
      body.run();
      return;
    }
    lane.runInline(() -> {
      try {
        body.run();
      } finally {
        endJob();
      }
    });
  }

  /** Lane-only. The flush that ends a job, and what comes after one: patches, for every tab here. */
  void endJob() {
    if (flushing) {
      return;
    }
    flushing = true;
    boolean again;
    try {
      do {
        for (Tab tab : tabs) {
          forTab(tab, tab::applyDirty);
        }
        Runnable task;
        while ((task = microtasks.poll()) != null) {
          runQuietly(task);
        }
        try {
          rt.flush();
        } catch (RuntimeException | Error e) {
          engine.log(System.Logger.Level.WARNING, "a flush failed", e);
        }
        if (rt.halted()) {
          // Solid stops a page for good here. These tabs go on (ADR 0033): only what failed keeps what it shows.
          rt.resetErrorHalt();
        }
      } while (!microtasks.isEmpty() || anyDirty());
      for (Tab tab : tabs) {
        forTab(tab, tab::afterFlush);
      }
      // What afterFlush ran, such as onSettled callbacks, may have written.
      again = !microtasks.isEmpty() || anyDirty();
    } finally {
      flushing = false;
    }
    if (again) {
      endJob();
      return;
    }
    jobsDone++;
    if (published != null) {
      try {
        published.afterJob();
      } catch (RuntimeException | Error e) {
        engine.log(System.Logger.Level.WARNING, "publishing a revision failed", e);
      }
    }
    writing = null;
    Tab t = tab;
    Stub stub = t == null || t.sharedFor == null ? null : t.sharedFor.stub;
    if (stub != null) {
      // What this job wrote to a store held on another server goes there as one batch (ADR 0037).
      stub.flush();
    }
    sendOutgoing();
  }

  // ---- other graphs (ADR 0037)

  /** How many jobs ended. Lane-only. */
  long jobsDone;

  /** The state each value other graphs read here was last published in, readable from any thread (ADR 0037). */
  final java.util.concurrent.ConcurrentHashMap<Cell, Published.State> lastStates =
    new java.util.concurrent.ConcurrentHashMap<>();

  /** The state cell was last published in, or null when no other graph reads it. Any thread. */
  Published.State lastState(Cell cell) {
    return lastStates.get(cell);
  }

  /** A graph of stores, not of a page. */
  boolean isStoreHost() {
    Tab t = tab;
    return t != null && t.isHub();
  }

  /** Runs when no other graph reads anything here any more: a shared store's hub may end then. Lane-only. */
  Runnable unread;

  /**
   * Whether code on this lane may wait for other's lane. A page's lane may wait for any store's; a store's only for a
   * younger store's, so that no two lanes ever wait for each other.
   */
  boolean mayInline(ReactiveHost other) {
    return other != this && (!isStoreHost() || other.seq > seq);
  }

  /** Lane-only. What other graphs read of this one, made with the first reader. */
  Published published() {
    if (published == null) {
      published = new Published(this);
    }
    return published;
  }

  /** Runs body at the end of this job, after the flush in progress. Lane-only. */
  void later(Runnable body) {
    microtasks.add(body);
  }

  /** Like hop, but only if no other task of the lane runs now; false when it did not run. */
  boolean tryHop(Runnable body) {
    if (lane.isCurrent()) {
      body.run();
      return true;
    }
    return lane.tryRunInline(() -> {
      try {
        body.run();
      } finally {
        endJob();
      }
    });
  }

  /** A write this graph's code made to another graph's value. */
  private static final class Outgoing {
    final ReactiveHost to;
    final Runnable write;

    Outgoing(ReactiveHost to, Runnable write) {
      this.to = to;
      this.write = write;
    }
  }
  /** The graph whose batch of writes this job applies, for what it publishes. Lane-only. */
  ReactiveHost writing;

  /**
   * Lane-only. A write to a value of the graph to, made by code running here (ADR 0037): the writes of one job go
   * there together, in their order, when the job ends, and are applied in one job of that graph's.
   */
  void queueWrite(ReactiveHost to, Runnable write) {
    outgoing.add(new Outgoing(to, write));
  }

  /** Lane-only. Where the writes of what runs next start, to drop them if it fails. */
  int outgoingMark() {
    return outgoing.size();
  }

  /** Lane-only. What failed sends nothing: its writes since mark are dropped. */
  void dropOutgoingSince(int mark) {
    while (outgoing.size() > mark) {
      outgoing.remove(outgoing.size() - 1);
    }
  }

  private void sendOutgoing() {
    if (outgoing.isEmpty()) {
      return;
    }
    List<Outgoing> sent = outgoing;
    outgoing = new ArrayList<>();
    java.util.Map<ReactiveHost, List<Runnable>> byHost = new java.util.LinkedHashMap<>();
    for (Outgoing o : sent) {
      byHost.computeIfAbsent(o.to, h -> new ArrayList<>()).add(o.write);
    }
    List<Batch> batches = new ArrayList<>();
    byHost.forEach((to, writes) -> batches.add(new Batch(to, writes)));
    deliver(batches, null);
  }

  /** One graph's share of a click's writes. */
  static final class Batch {
    final ReactiveHost to;
    final List<Runnable> writes;

    Batch(ReactiveHost to, List<Runnable> writes) {
      this.to = to;
      this.writes = writes;
    }
  }

  /**
   * Lane-only. Applies a click's writes to the graphs they are for, as one change: a page's graph waits for each
   * graph's lane in their order and applies every batch before any publishes, so a click's writes to two stores land
   * in both at once. A store's graph waits for no older lane: each batch goes as a job of its own there. done, if any,
   * runs in a later job of this graph's, after the revisions they made had reached it.
   */
  void deliver(List<Batch> batches, java.util.function.Consumer<Throwable> done) {
    if (isStoreHost()) {
      int[] left = {batches.size()};
      for (Batch batch : batches) {
        batch.to.applyBatch(this, batch.writes, () -> {
          if (--left[0] == 0 && done != null) {
            job(() -> done.accept(null));
          }
        });
      }
      return;
    }
    List<ReactiveHost> targets = new ArrayList<>();
    for (Batch batch : batches) {
      if (!targets.contains(batch.to)) {
        targets.add(batch.to);
      }
    }
    targets.sort(java.util.Comparator.comparingLong(h -> h.seq));
    List<CompletableFuture<Void>> remote = new ArrayList<>();
    Runnable body = () -> {
      for (Batch batch : batches) {
        batch.to.applyHere(this, batch.writes);
      }
    };
    for (int i = targets.size() - 1; i >= 0; i--) {
      ReactiveHost target = targets.get(i);
      Runnable inner = body;
      body = () -> {
        Stub.takeSent();
        target.hop(inner);
        // A stub sent what it took on to the server holding its store: done waits for that server too (ADR 0037).
        Tab hub = target.tab;
        Stub stub = hub == null || hub.sharedFor == null ? null : hub.sharedFor.stub;
        CompletableFuture<Void> answered = stub == null ? null : Stub.takeSent();
        if (answered != null) {
          remote.add(answered);
        }
      };
    }
    body.run();
    if (done != null) {
      if (remote.isEmpty()) {
        // After the revisions the targets posted while this lane waited for them.
        job(() -> done.accept(null));
      } else {
        CompletableFuture.allOf(remote.toArray(new CompletableFuture<?>[0]))
          .whenComplete((v, e) -> job(() -> done.accept(e)));
      }
    }
  }

  /** On this lane, held by a page's delivery: another graph's writes, applied as its, in their order. */
  private void applyHere(ReactiveHost from, List<Runnable> writes) {
    Tab owner = tab;
    if (owner == null || owner.disposed) {
      return;
    }
    writing = from;
    Tracking.in(owner, null, () -> {
      for (Runnable write : writes) {
        runQuietly(write);
      }
      return null;
    });
  }

  /**
   * Applies writes from another graph in one job, in their order, so that they are one revision; then runs done, after
   * that revision went out. From any thread.
   */
  void applyBatch(ReactiveHost from, List<Runnable> writes, Runnable done) {
    job(() -> {
      Tab owner = tab;
      if (owner == null || owner.disposed) {
        return;
      }
      writing = from;
      Tracking.in(owner, null, () -> {
        for (Runnable write : writes) {
          runQuietly(write);
        }
        return null;
      });
    });
    if (done != null) {
      job(done);
    }
  }

  /** Runs a step for one tab as that tab's, so a failure is logged as its own. */
  private void forTab(Tab tab, Runnable step) {
    try {
      Tracking.in(tab, null, () -> {
        step.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      engine.log(System.Logger.Level.ERROR, "tab task failed", e);
    }
  }

  /**
   * A write from another thread (ADR 0014). Those that arrive before the lane gets to them are one job, so a burst is
   * one flush and one patch, which is what keeps a chatty producer from rendering once per value.
   */
  void writeFromOutside(Runnable write) {
    foreignWrites.add(write);
    if (foreignWriteQueued.compareAndSet(false, true)) {
      job(() -> {
        foreignWriteQueued.set(false);
        Runnable next;
        while ((next = foreignWrites.poll()) != null) {
          runQuietly(next);
        }
      });
    }
  }

  private boolean anyDirty() {
    for (Tab tab : tabs) {
      if (tab.hasDirty()) {
        return true;
      }
    }
    return false;
  }

  private void runQuietly(Runnable task) {
    try {
      task.run();
    } catch (RuntimeException | Error e) {
      engine.log(System.Logger.Level.WARNING, "a reactive task failed", e);
    }
  }
}
