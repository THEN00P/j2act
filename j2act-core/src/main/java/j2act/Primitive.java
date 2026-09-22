package j2act;

/**
 * A handle to a reactive cell. Handles made in field initializers stay unbound until
 * the component is placed in the tree; then each binds to its slot's cell by creation
 * order, creating it on first mount (ADR 0019).
 */
abstract class Primitive {

  Cell cell;

  abstract Cell createCell(Session session, String address);

  /** Whether an existing cell at this index is of this primitive's kind. */
  abstract boolean accepts(Cell cell);

  /** Binds this handle to an existing or freshly created cell. Lane-only. */
  abstract void attach(Cell cell, boolean created);

  final Cell requireCell() {
    if (cell == null) {
      throw new IllegalStateException("primitive is not bound yet; it binds when its component is rendered");
    }
    return cell;
  }
}
