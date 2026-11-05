package j2act;

/**
 * Thread-bound tracking context. On the lane it names the observer whose reads are
 * being recorded; on an executor thread running a query loader it names the run.
 * Both are always cleared in a finally (ADR 0014).
 */
final class Tracking {

  private static final ThreadLocal<Observer> OBSERVER = new ThreadLocal<>();
  private static final ThreadLocal<LoadRun> RUN = new ThreadLocal<>();
  /** The component or store scope whose code is running, for what is read around it (ADR 0031). */
  private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

  private Tracking() {
  }

  static Observer observer() {
    return OBSERVER.get();
  }

  /** Installs an observer and returns the previous one, to restore in a finally. */
  static Observer swap(Observer next) {
    Observer previous = OBSERVER.get();
    if (next == null) {
      OBSERVER.remove();
    } else {
      OBSERVER.set(next);
    }
    return previous;
  }

  static Scope scope() {
    return SCOPE.get();
  }

  /** Installs the running scope and returns the previous one, to restore in a finally. */
  static Scope swapScope(Scope next) {
    Scope previous = SCOPE.get();
    if (next == null) {
      SCOPE.remove();
    } else {
      SCOPE.set(next);
    }
    return previous;
  }

  /** What the running computation sees of a node's three places (ADR 0031). */
  private static final ThreadLocal<Pass> PASS = new ThreadLocal<>();

  static Pass pass() {
    return PASS.get();
  }

  /** Installs a pass and returns the previous one, to restore in a finally. */
  static Pass swapPass(Pass next) {
    Pass previous = PASS.get();
    if (next == null) {
      PASS.remove();
    } else {
      PASS.set(next);
    }
    return previous;
  }

  /** isPending(...) is probing what its expression reads (ADR 0031). */
  private static final ThreadLocal<boolean[]> PROBE = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> UNTRACKED = new ThreadLocal<>();

  static boolean[] probe() {
    return PROBE.get();
  }

  static boolean[] swapProbe(boolean[] next) {
    boolean[] previous = PROBE.get();
    if (next == null) {
      PROBE.remove();
    } else {
      PROBE.set(next);
    }
    return previous;
  }

  /** On a run's thread, inside untrack(...): a read here is deliberately followed by nothing. */
  static boolean untracked() {
    return UNTRACKED.get() != null;
  }

  static LoadRun run() {
    return RUN.get();
  }

  static void setRun(LoadRun run) {
    RUN.set(run);
  }

  static void clearRun() {
    RUN.remove();
  }

  /** Stops recording this run's reads, and returns it to hand back to resumeRun. */
  static LoadRun suspendRun() {
    LoadRun run = RUN.get();
    if (run != null) {
      RUN.remove();
      UNTRACKED.set(Boolean.TRUE);
    }
    return run;
  }

  static void resumeRun(LoadRun run) {
    if (run != null) {
      UNTRACKED.remove();
      RUN.set(run);
    }
  }
}
