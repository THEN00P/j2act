package j2act;

/** The component or store scope whose code runs on this thread, for what is read around it (ADR 0031). */
final class Tracking {

  private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

  private Tracking() {
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
}
