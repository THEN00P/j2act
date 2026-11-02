package j2act;

/**
 * Thrown by reading an async memo whose last load failed (ADR 0031): rendering stops up to the
 * nearest errored(...), whose fallback gets the cause.
 */
final class LoadFailed extends Error {

  private static final long serialVersionUID = 1L;

  final transient AsyncCell cell;

  LoadFailed(AsyncCell cell, Throwable cause) {
    super(cause == null ? "load failed" : cause.toString(), cause, false, false);
    this.cell = cell;
  }
}
