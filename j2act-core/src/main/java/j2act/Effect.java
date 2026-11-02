package j2act;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Handle for createEffect() (ADR 0031): what it watches, and what it does with the watched value. */
final class Effect extends Primitive {

  private final Supplier<?> track;
  private final BiFunction<Object, Object, Runnable> apply;

  Effect(Supplier<?> track, BiFunction<Object, Object, Runnable> apply) {
    this.track = track;
    this.apply = apply;
  }

  @Override Cell createCell(Tab tab, String address) {
    return new EffectCell(tab, address);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof EffectCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    EffectCell effect = (EffectCell) cell;
    effect.track = track;
    effect.apply = apply;
    if (created) {
      effect.schedule();
    }
  }
}
