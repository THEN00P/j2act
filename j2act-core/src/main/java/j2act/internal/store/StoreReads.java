package j2act.internal.store;

/**
 * What compiled code calls (ADR 0035): the bytecode enhancer puts these calls where a {@link Tracked} class reads
 * its fields, so a reader subscribes to each field it reads. They are public for that code, not for applications.
 */
public final class StoreReads {
  private StoreReads() {
  }

  /** What runs when a read finds its object out of date; the engine installs it (ADR 0036). */
  private static volatile Runnable stale = () -> { };

  /** Called once by the engine. */
  public static void whenStale(Runnable run) {
    stale = run;
  }

  /**
   * What the enhancer compiles a read of a tracked class's field to (ADR 0035, 0036): the reader subscribes to the field,
   * and gets own, the value the object holds, for an object handed out is that version and never changes. When the
   * store has another value there now, the object is out of date, as one a region kept from its view's last run can
   * be: the engine then runs again what handed it out, which hands out the current version, as a plain build's view,
   * subscribed to the whole object, would have run again.
   */
  public static Object value(Object owner, Object own, String name, Class<?> type) {
    Object record = owner instanceof Tracked ? ((Tracked) owner).j2act$record() : null;
    if (record instanceof BeanView) {
      BeanView view = (BeanView) record;
      Object now = view.shape.readField(view, name, own, type);
      // An object by identity: a store hands out the same snapshot until something in it changes, and a new one
      // after, even when it equals the old one, as an entity equal by its id does. A scalar by its value.
      boolean same = now == own || (scalar(now) && java.util.Objects.equals(now, own));
      if (!same) {
        stale.run();
      }
    }
    return own;
  }

  /**
   * owner is about to read its field name. When a store built owner, the reader subscribes to that field, and to
   * what the field holds where that cannot track itself: a list's or a map's size and slots, and the whole of an
   * object whose class is not tracked. An object no store built subscribes to nothing.
   */
  private static boolean scalar(Object value) {
    return value instanceof String || value instanceof Number || value instanceof Boolean
      || value instanceof Character || value instanceof Enum;
  }

  public static void field(Object owner, String name) {
    Object record = owner instanceof Tracked ? ((Tracked) owner).j2act$record() : null;
    if (record instanceof BeanView) {
      BeanView view = (BeanView) record;
      view.shape.trackField(view, name);
    }
  }
}
