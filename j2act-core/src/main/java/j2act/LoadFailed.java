package j2act;

/**
 * Thrown by reading an async memo whose last load failed (ADR 0031): rendering stops up to the
 * nearest errored(...), whose fallback gets the cause.
 */
final class LoadFailed extends Error {

  private static final long serialVersionUID = 1L;

  final transient Node cell;

  LoadFailed(Node cell, Throwable cause) {
    super(cause == null ? "load failed" : cause.toString(), cause, false, false);
    this.cell = cell;
  }
}
