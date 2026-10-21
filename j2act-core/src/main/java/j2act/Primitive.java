package j2act;

/**
 * A handle to a reactive cell. Handles made in field initializers stay unbound until
 * the component is placed in the tree; then each binds to its slot's cell by creation
 * order, creating it on first mount (ADR 0019).
 */
abstract class Primitive {

  Cell cell;
  /** The store whose field this is, which binds on first need while its tab makes it (ADR 0030). */
  Store store;

  abstract Cell createCell(Tab tab, String address);

  /** Whether an existing cell at this index is of this primitive's kind. */
  abstract boolean accepts(Cell cell);

  /**
   * Whether this object holds code or settings beyond its cell, such as a query's loader or a
   * query state's parameter name, which a kept child's last object would go on using instead of
   * a fresh description's (ADR 0031). Only a handle whose cell is all that matters once it exists says no.
   */
  boolean carriesSettings() {
    return true;
  }

  /** Binds this handle to an existing or freshly created cell. Lane-only. */
  abstract void attach(Cell cell, boolean created);

  /** This handle's cell, binding a store its tab is still making first; null while unbound. */
  final Cell cellOrNull() {
    if (cell == null && store != null) {
      store.bindIfPending();
    }
    return cell;
  }

  final Cell requireCell() {
    if (cell == null && store != null) {
      // A container's @PostConstruct runs before the tab binds the store it is making: bind now.
      store.bindIfPending();
    }
    if (cell == null) {
      throw new IllegalStateException("primitive is not bound yet; it binds when its component is rendered");
    }
    return cell;
  }
}
