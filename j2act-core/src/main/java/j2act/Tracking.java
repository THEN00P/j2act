package j2act;

/** The component or store scope whose code runs on this thread, for what is read around it (ADR 0031). */
final class Tracking {

  private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

  private Tracking() {
  }

  static Scope scope() {
    return SCOPE.get();
  }

  /**
   * Runs fn with what is around it put back: a browser's tabs share a runtime, so code that runs again later, in
   * whatever job its flush falls in, has only this to say whose it is (ADR 0033).
   */
  static <T> T in(Tab tab, Scope scope, java.util.function.Supplier<T> fn) {
    Scope previousScope = swapScope(scope);
    Tab previousTab = Tab.swapCurrent(tab);
    try {
      return fn.get();
    } finally {
      swapScope(previousScope);
      Tab.swapCurrent(previousTab);
    }
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
