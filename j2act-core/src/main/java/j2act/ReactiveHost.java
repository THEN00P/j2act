package j2act;

import j2act.internal.signals.Runtime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * What one browser's tabs share (ADR 0033): one lane, one graph and one scheduler, as Solid has one of each
 * per page. A tab with no cookie has one of its own. The engine's microtask is the end of the lane job
 * that scheduled it, so a job is what JS's synchronous code is, and every job ends with a flush.
 */
final class ReactiveHost {

  final J2Act engine;
  final Lane lane;
  final Runtime rt;
  /** What the engine scheduled for the end of this job. Lane-only. */
  private final Deque<Runnable> microtasks = new ArrayDeque<>();
  /** Every tab on this lane, hub included. */
  final Set<Tab> tabs = ConcurrentHashMap.newKeySet();
  private boolean flushing;
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
      if (!microtasks.isEmpty() || anyDirty()) {
        flushing = false;
        endJob();
      }
    } finally {
      flushing = false;
    }
  }

  /** Runs a step for one tab as that tab's: if it fails, the other tabs of this browser still get theirs. */
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
