package j2act.internal.store;

/**
 * What compiled code calls (ADR 0035): the bytecode enhancer puts these calls where a {@link Tracked} class reads
 * its fields, so a reader subscribes to each field it reads. They are public for that code, not for applications.
 */
public final class StoreReads {
  private StoreReads() {
  }

  /**
   * owner is about to read its field name. When a store built owner, the reader subscribes to that field, and to
   * what the field holds where that cannot track itself: a list's or a map's size and slots, and the whole of an
   * object whose class is not tracked. An object no store built subscribes to nothing.
   */
  public static void field(Object owner, String name) {
    Object record = owner instanceof Tracked ? ((Tracked) owner).j2act$record() : null;
    if (record instanceof BeanView) {
      BeanView view = (BeanView) record;
      view.shape.trackField(view, name);
    }
  }
}
