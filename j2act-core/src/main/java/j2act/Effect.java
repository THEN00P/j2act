package j2act;

import java.util.function.Supplier;

/** Handle for effect(); the body returns its cleanup or null. */
final class Effect extends Primitive {

  private final Supplier<Runnable> body;

  Effect(Supplier<Runnable> body) {
    this.body = body;
  }

  @Override Cell createCell(Session session, String address) {
    return new EffectCell(session, address, body);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof EffectCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    ((EffectCell) cell).body = body;
    if (created) {
      ((EffectCell) cell).schedule();
    }
  }
}
