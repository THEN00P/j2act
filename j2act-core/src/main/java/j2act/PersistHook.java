package j2act;

/** Handle for onPersisting(): its callback runs right before the session's snapshot is taken (ADR 0026). */
final class PersistHook extends Primitive {

  private final Runnable callback;

  PersistHook(Runnable callback) {
    this.callback = java.util.Objects.requireNonNull(callback, "callback");
  }

  @Override Cell createCell(Session session, String address) {
    return new PersistCell(session, address);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof PersistCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    ((PersistCell) cell).callback = callback;
  }
}
