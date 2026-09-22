package j2act;

/**
 * Thread-bound tracking context. On the lane it names the observer whose reads are
 * being recorded; on an executor thread running a query loader it names the run.
 * Both are always cleared in a finally (ADR 0014).
 */
final class Tracking {

  private static final ThreadLocal<Observer> OBSERVER = new ThreadLocal<>();
  private static final ThreadLocal<QueryRun> RUN = new ThreadLocal<>();

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

  static QueryRun run() {
    return RUN.get();
  }

  static void setRun(QueryRun run) {
    RUN.set(run);
  }

  static void clearRun() {
    RUN.remove();
  }
}
