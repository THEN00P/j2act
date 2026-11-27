package j2act;

/**
 * The cells of a stub of a shared store held on another server (ADR 0037): a value or a deep store fed by the holder for
 * each primitive that holds one, and for an effect or a lifecycle hook a cell that never runs, since those run on the
 * holder only.
 */
final class Stubs {
  private Stubs() {
  }

  static Cell cell(Primitive primitive, Tab tab, String address, Stub stub, Store store) {
    if (primitive instanceof Stored || primitive instanceof Projection) {
      java.lang.reflect.Type type = Ops.typeOf(store, address);
      boolean optimistic = primitive instanceof Stored && ((Stored<?>) primitive).isOptimistic();
      return StubStoreCell.of(tab, address, stub, type, optimistic);
    }
    if (primitive instanceof Signal || primitive instanceof Memo || primitive instanceof Optimistic
        || primitive instanceof RetainedValue) {
      return StubValueCell.of(tab, address, stub);
    }
    if (primitive instanceof Effect || primitive instanceof Lifecycle) {
      return primitive.createCell(tab, address);
    }
    throw new IllegalStateException(primitive.getClass().getSimpleName() + " in " + store.getClass().getName()
      + " has no place in a @SharedScoped store another server holds (ADR 0037)");
  }

  /** An effect or a lifecycle hook of a stub is bound and never runs. */
  static boolean runs(Primitive primitive) {
    return !(primitive instanceof Effect || primitive instanceof Lifecycle);
  }
}
