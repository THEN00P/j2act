package j2act.internal.store;

/**
 * A class whose reads of its own fields track themselves (ADR 0035): what the bytecode enhancer makes of an
 * application's bean or record. Before each read of a field it calls {@link StoreReads#field}, and it keeps the
 * record a store built it from in a field of its own, named with {@link #PREFIX} so a store does not copy it.
 * A store hands such an object out subscribing to nothing, so whoever reads it subscribes to the fields it reads
 * and no more. Without the enhancer no class is one, and a reader subscribes to the whole object it is handed,
 * which shows the same values and runs more often.
 */
public interface Tracked {
  /** Fields a store leaves out of what it copies. */
  String PREFIX = "j2act$";

  /** The record this object was built from, as {@link #j2act$record(Object)} set it; null for one no store built. */
  Object j2act$record();

  void j2act$record(Object record);
}
