package j2act;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What createRetained(save, restore) returns (ADR 0031): live data that needs converting, or
 * several signals saved as one. Saving is tracked, so a checkpoint writes only when the saved value
 * changed; restoring runs during setup, so the first HTML already shows it.
 */
public final class RetainedValue<T> extends Primitive {

  final String name;
  final String stackName;
  private final Supplier<T> save;
  private final Consumer<T> restore;

  RetainedValue(Supplier<T> save, Consumer<T> restore, String name, String stackName) {
    this.save = save;
    this.restore = restore;
    this.name = name;
    this.stackName = stackName;
  }

  /** Whether this one came back from a snapshot, for a "your draft came back" message. */
  public boolean restored() {
    return ((ValueCell) requireCell()).restored;
  }

  @Override Cell createCell(Tab tab, String address) {
    return new SavedCell(tab, address, save, restore);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof SavedCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
