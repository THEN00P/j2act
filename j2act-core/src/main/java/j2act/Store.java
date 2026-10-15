package j2act;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * State shared beyond one component (ADR 0030): a class annotated {@link TabScoped} that
 * extends Store has one instance per tab. With Spring or CDI it is a bean of the app's
 * container, so components {@code @Inject} it and it injects what it needs; without one,
 * components reach it with tab(Type.class).
 *
 * <pre>{@code
 * @TabScoped
 * public class EditorContext extends Store {
 *   private final State<String> selected = state(null);
 *
 *   public String selected() { return selected.get(); }
 *   public void select(String id) { selected.set(id); }
 * }
 * }</pre>
 *
 * Its values are State, Retained State, Computeds, Queries and Mutations, declared as fields:
 * a plain field changes without re-rendering anything. Its methods are the actions. A Retained
 * State here is keyed by the store's class and field name. No method of a store may be final,
 * since containers proxy scoped beans by subclassing them.
 */
public abstract class Store {

  /** Primitives its field initializers created, bound when its tab takes the instance. */
  final List<Primitive> primitives = new ArrayList<>();
  Scope scope;

  protected Store() {
  }

  protected <T> State<T> state(T initial) {
    return register(new State<>(initial));
  }

  /**
   * A State whose value outlives the in-memory tab (ADR 0026, 0030): it comes back after the
   * grace window, a pause, a restart or a redeploy, while a reload still starts empty. Keyed
   * by this store's class and the field holding it. Replace values through set(), since
   * changing a retained list in place does not count as a change.
   */
  protected <T> State<T> retainedState(T initial) {
    return register(new State<>(initial, true, null));
  }

  /** A Retained State with an explicit name instead of its field's name; never required. */
  protected <T> State<T> retainedState(String name, T initial) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("a retained state name must not be empty");
    }
    return register(new State<>(initial, true, name));
  }

  /** A pure derived value, recomputed when what it read changes (CONTEXT: Computed). */
  protected <T> Computed<T> computed(Supplier<T> body) {
    return register(new Computed<>(body));
  }

  /** A cached async loader; State it reads is its dependency (ADR 0020). */
  protected <T> Query<T> query(Loader<T> loader) {
    return register(new Query<>(loader));
  }

  /** An async write: status, data, error and variables are tracked (ADR 0006). */
  protected <V, R> Mutation<V, R> mutation(Mutation.Body<V, R> body) {
    return register(new Mutation<>(body));
  }

  /** Runs once the store is created and whenever a State it read changes; returns its cleanup or null. */
  protected void effect(Supplier<Runnable> body) {
    register(new Effect(body));
  }

  /** Runs right before the tab's snapshot is taken, so the store can copy values into its retained fields (ADR 0026). */
  protected void onPersisting(Runnable callback) {
    register(new PersistHook(callback));
  }

  private <P extends Primitive> P register(P primitive) {
    if (scope != null) {
      throw new IllegalStateException(getClass().getName()
        + " creates a primitive after it was created; declare a store's primitives as fields (ADR 0030)");
    }
    primitives.add(primitive);
    return primitive;
  }
}
