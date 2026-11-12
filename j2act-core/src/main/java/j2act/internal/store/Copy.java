package j2act.internal.store;

/**
 * The store's own copy of a bean or a record (ADR 0035): its fields as plain values, which the store writes and
 * which hold the store's copies of what is below them. A typed field cannot hold those, so the real object is
 * built from this when it is handed out, a bean through its constructor without arguments and a record through
 * its canonical constructor. The object the store was given is never written.
 */
final class Copy {
  final Kinds.Layout layout;
  final Object[] values;

  Copy(Kinds.Layout layout, Object[] values) {
    this.layout = layout;
    this.values = values;
  }

  @Override public String toString() {
    return layout.type.getSimpleName() + java.util.Arrays.toString(values);
  }
}
