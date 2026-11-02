package j2act.internal.signals;

/** Port of @solidjs/signals core/error.ts at 9449845. */
public final class Errors {
  private Errors() {
  }

  /** A read of a value not settled yet. An Error, so that a plain catch (Exception e) does not swallow it. */
  public static final class NotReadyError extends Error {
    private static final long serialVersionUID = 1L;
    public final transient Node source;

    public NotReadyError(Node source) {
      super("not ready", null, false, false);
      this.source = source;
    }
  }

  /** A failure, wrapped with the node it came from. */
  public static final class StatusError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final transient Node source;

    public StatusError(Node source, Throwable original) {
      super(original == null ? null : String.valueOf(original.getMessage()), original);
      this.source = source;
    }
  }

  /** until() ran out of time. */
  public static final class TimeoutError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TimeoutError() {
      super("until() timed out before the condition was met.");
    }
  }

  /** Context needs an owner. */
  public static final class NoOwnerError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NoOwnerError() {
      super("Context can only be accessed under a reactive root.");
    }
  }

  /** A context with no default and no value. */
  public static final class ContextNotFoundError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ContextNotFoundError() {
      super("Context must either be created with a default value or a value must be provided before accessing it.");
    }
  }

  static Throwable unwrapStatusError(Throwable error) {
    return error instanceof StatusError ? error.getCause() : error;
  }

  /** Rethrows what Solid would, without Java's checked-exception wrapping. */
  @SuppressWarnings("unchecked")
  static <E extends Throwable> RuntimeException sneak(Throwable t) throws E {
    throw (E) t;
  }
}
