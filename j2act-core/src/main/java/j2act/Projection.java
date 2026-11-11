package j2act;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A list kept up to date by a derive, created with createProjection (ADR 0035): each answer is merged into
 * the last by key, so a row that came back keeps its place and only what changed in it re-runs its readers.
 * It is read like a store and written by nobody but its derive.
 *
 * <pre>{@code
 * var todos = createProjection(() -> async(() -> repo.list()), Todo::getId);
 * return () -> loading(() -> each(todos.get(), t -> li(t.getTitle())));
 * }</pre>
 */
public final class Projection<E> extends Primitive implements Supplier<List<E>> {
  private final java.util.concurrent.Callable<Object> body;
  private final Function<Object, Object> key;

  Projection(java.util.concurrent.Callable<Object> body, Function<Object, Object> key) {
    this.body = body;
    this.key = key;
  }

  /** The rows: a List whose reads subscribe to a row or the size. While the first answer loads, a read stops at loading(...). */
  @Override
  @SuppressWarnings("unchecked")
  public List<E> get() {
    return (List<E>) ((StoreCell) requireCell()).value();
  }

  @Override Cell createCell(Tab tab, String address) {
    return new StoreCell(tab, address, body, null, key, false);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof StoreCell && ((StoreCell) cell).deriveNode() != null;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
