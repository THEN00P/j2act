package j2act;

/**
 * Thrown by reading an async memo that has no value yet (ADR 0031): rendering stops up to the
 * nearest loading(...), or the page waits. An Error, so a plain catch (Exception e) in a view
 * does not swallow it.
 */
final class NotReady extends Error {

  private static final long serialVersionUID = 1L;

  final transient Cell cell;

  NotReady(Cell cell) {
    super("not loaded yet", null, false, false);
    this.cell = cell;
  }
}
