package j2act;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Handle for createEffect() (ADR 0031): what it watches, and what it does with the watched value. */
final class Effect extends Primitive {

  private final Supplier<Runnable> body;
  private final Supplier<?> track;
  private final BiFunction<Object, Object, Runnable> apply;
  /** Runs at the HTML render instead of waiting for the socket; only onRestored until it goes. */
  boolean immediate;

  Effect(Supplier<Runnable> body) {
    this.body = body;
    this.track = null;
    this.apply = null;
  }

  Effect(Supplier<?> track, BiFunction<Object, Object, Runnable> apply) {
    this.body = null;
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
    effect.body = body;
    effect.track = track;
    effect.apply = apply;
    effect.immediate = immediate;
    if (created) {
      effect.schedule();
    }
  }
}
