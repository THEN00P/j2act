package j2act;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Where blocking work runs (ADR 0037): async(...) bodies, action bodies and transfers. It shares the executor with the
 * lanes, which have to run where the container's context is, so it may use only part of it, and the rest is always
 * there for rendering and stores. Within that part, each tab or store gets its turn: one user's slow database calls
 * wait behind each other, not in front of everyone else's. Work started from inside work runs at once, so a load that
 * waits for a load it started never waits for a turn its own wait holds; and work that waited half a second starts
 * whatever the limits say, so a body blocked on work queued behind it can never hold everything up.
 */
final class Work {

  private static final ThreadLocal<Boolean> IN_WORK = new ThreadLocal<>();

  private final Executor executor;
  private final int max;
  private final int perOwner;
  private final J2Act engine;

  /** Each owner's queue and how many of its tasks run. Guarded by this. */
  private static final class Owner {
    final Object key;
    final ArrayDeque<Task> queue = new ArrayDeque<>();
    int running;

    Owner(Object key) {
      this.key = key;
    }
  }

  private static final class Task {
    final Runnable body;
    final Runnable rejected;
    final long queuedAt = System.nanoTime();

    Task(Runnable body, Runnable rejected) {
      this.body = body;
      this.rejected = rejected;
    }
  }

  /** How long work waits for its turn before it starts anyway. */
  private static final long OVERDUE_MILLIS = 500;
  private static final long OVERDUE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(OVERDUE_MILLIS);

  private final Map<Object, Owner> owners = new HashMap<>();
  /** Owners with work waiting, in turn order. Guarded by this. */
  private final ArrayDeque<Owner> turns = new ArrayDeque<>();
  private int running;

  Work(J2Act engine, Executor executor, int max, int perOwner) {
    this.engine = engine;
    this.executor = executor;
    this.max = Math.max(1, max);
    this.perOwner = Math.max(1, perOwner);
  }

  /**
   * Runs body for owner, a tab, when its turn comes; rejected runs instead when the executor refuses it, so what
   * waits on body hears of it.
   */
  void execute(Object owner, Runnable body, Runnable rejected) {
    if (Boolean.TRUE.equals(IN_WORK.get())) {
      submit(null, new Task(body, rejected));
      return;
    }
    synchronized (this) {
      Owner o = owners.computeIfAbsent(owner, Owner::new);
      o.queue.add(new Task(body, rejected));
      if (o.queue.size() == 1 && !turns.contains(o)) {
        turns.add(o);
      }
      startWhatFits();
      if (!o.queue.isEmpty()) {
        engine.afterMillis(OVERDUE_MILLIS, this::startOverdue);
      }
    }
  }

  /** Starts what waited too long for its turn, over the limits if it has to. */
  private synchronized void startOverdue() {
    long now = System.nanoTime();
    for (Owner o : new java.util.ArrayList<>(owners.values())) {
      while (!o.queue.isEmpty() && now - o.queue.peek().queuedAt >= OVERDUE_NANOS) {
        Task task = o.queue.poll();
        if (o.queue.isEmpty()) {
          turns.remove(o);
        }
        o.running++;
        running++;
        submit(o, task);
      }
    }
    boolean waiting = false;
    for (Owner o : owners.values()) {
      waiting |= !o.queue.isEmpty();
    }
    if (waiting) {
      engine.afterMillis(OVERDUE_MILLIS, this::startOverdue);
    }
  }

  /** Holding this: starts queued tasks while there is room, one owner's at a time, in turn. */
  private void startWhatFits() {
    int skipped = 0;
    while (running < max && !turns.isEmpty() && skipped < turns.size()) {
      Owner o = turns.poll();
      if (o.running >= perOwner) {
        turns.add(o);
        skipped++;
        continue;
      }
      skipped = 0;
      Task task = o.queue.poll();
      if (!o.queue.isEmpty()) {
        turns.add(o);
      }
      o.running++;
      running++;
      submit(o, task);
    }
  }

  private void submit(Owner owner, Task task) {
    Runnable run = () -> {
      Boolean outer = IN_WORK.get();
      IN_WORK.set(Boolean.TRUE);
      try {
        task.body.run();
      } finally {
        if (outer == null) {
          IN_WORK.remove();
        }
        if (owner != null) {
          done(owner);
        }
      }
    };
    try {
      executor.execute(run);
    } catch (RejectedExecutionException e) {
      if (owner != null) {
        done(owner);
      }
      try {
        task.rejected.run();
      } catch (RuntimeException failure) {
        engine.log(System.Logger.Level.WARNING, "blocking work was refused by the executor", failure);
      }
    }
  }

  private synchronized void done(Owner owner) {
    owner.running--;
    running--;
    if (owner.running == 0 && owner.queue.isEmpty()) {
      owners.remove(owner.key);
      turns.remove(owner);
    } else if (!owner.queue.isEmpty() && !turns.contains(owner)) {
      turns.add(owner);
    }
    startWhatFits();
  }

  /** How many tasks run now, for tests. */
  synchronized int running() {
    return running;
  }

  /**
   * How much of executor blocking work may use: three quarters of a pool whose size is known, so a quarter is always
   * there for the lanes; 8 threads of one whose size is not, which an adapter or the application sets when it knows.
   */
  static int share(Executor executor) {
    if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
      java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) executor;
      // With a queue that never fills, a pool never grows past its core.
      int threads = pool.getQueue().remainingCapacity() == Integer.MAX_VALUE ? pool.getCorePoolSize()
        : pool.getMaximumPoolSize();
      return Math.max(1, threads * 3 / 4);
    }
    if (executor instanceof java.util.concurrent.ForkJoinPool) {
      return Math.max(1, ((java.util.concurrent.ForkJoinPool) executor).getParallelism() * 3 / 4);
    }
    return 8;
  }
}
