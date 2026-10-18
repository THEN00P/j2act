package j2act;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serial execution over a shared pool: at most one task of a tab runs at a time,
 * so the tab's graph needs no locks (ADR 0001). Tasks never run on socket threads.
 */
final class Lane implements Executor {

  private static final int BATCH = 64;

  private final Executor pool;
  private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicInteger backlog = new AtomicInteger();
  private volatile Thread owner;
  /** Held while one of this lane's tasks runs, by drain or inline; reentrant for a lane running itself. */
  private final java.util.concurrent.locks.ReentrantLock running = new java.util.concurrent.locks.ReentrantLock();

  Lane(Executor pool) {
    this.pool = pool;
  }

  @Override public void execute(Runnable task) {
    backlog.incrementAndGet();
    queue.add(task);
    schedule();
  }

  boolean isCurrent() {
    return owner == Thread.currentThread();
  }

  /** Tasks queued and not yet started. */
  int backlog() {
    return backlog.get();
  }

  /** More tasks are waiting; the current one may leave flushing to the last of them. */
  boolean hasQueued() {
    return !queue.isEmpty();
  }

  private void schedule() {
    if (scheduled.compareAndSet(false, true)) {
      pool.execute(this::drain);
    }
  }

  /**
   * Runs a task as this lane's, on the calling thread, once no other task of the lane runs. It
   * never waits for a pool thread, so a lane can use another lane even when the pool has one
   * thread (ADR 0030). The task must not wait on the calling thread's own lane.
   */
  void runInline(Runnable task) {
    running.lock();
    Thread previous = owner;
    owner = Thread.currentThread();
    try {
      task.run();
    } finally {
      owner = previous;
      running.unlock();
    }
  }

  private void drain() {
    try {
      Runnable task;
      int ran = 0;
      while (ran++ < BATCH && (task = queue.poll()) != null) {
        backlog.decrementAndGet();
        running.lock();
        owner = Thread.currentThread();
        try {
          task.run();
        } catch (Throwable t) {
          System.getLogger("j2act").log(System.Logger.Level.ERROR, "lane task failed", t);
        } finally {
          owner = null;
          running.unlock();
        }
      }
    } finally {
      scheduled.set(false);
      if (!queue.isEmpty()) {
        schedule();
      }
    }
  }
}
