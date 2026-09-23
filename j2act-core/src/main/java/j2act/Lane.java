package j2act;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serial execution over a shared pool: at most one task of a session runs at a time,
 * so the session's graph needs no locks (ADR 0001). Tasks never run on socket threads.
 */
final class Lane implements Executor {

  private static final int BATCH = 64;

  private final Executor pool;
  private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicInteger backlog = new AtomicInteger();
  private volatile Thread owner;

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

  private void drain() {
    owner = Thread.currentThread();
    try {
      Runnable task;
      int ran = 0;
      while (ran++ < BATCH && (task = queue.poll()) != null) {
        backlog.decrementAndGet();
        try {
          task.run();
        } catch (Throwable t) {
          System.getLogger("j2act").log(System.Logger.Level.ERROR, "lane task failed", t);
        }
      }
    } finally {
      owner = null;
      scheduled.set(false);
      if (!queue.isEmpty()) {
        schedule();
      }
    }
  }
}
