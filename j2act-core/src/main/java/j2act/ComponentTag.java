package j2act;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A reusable component: render() returns one root element. Reactive primitives may
 * be field initializers or locals in render(); either way they bind to this
 * component's tree slot by creation order (ADR 0019). A held instance keeps its
 * identity by reference; a factory call builds a fresh description each render.
 */
public abstract class ComponentTag implements DomContent {

  /** Primitives created before the component was first placed (field initializers). */
  final List<Primitive> primitives = new ArrayList<>();
  Scope scope;
  Object key;
  private int renderIndex;

  protected abstract Tag<?> render();

  /** Slot identity among repeated siblings; never rendered (ADR 0019). */
  public ComponentTag withKey(Object key) {
    this.key = key;
    return this;
  }

  protected final <T> State<T> state(T initial) {
    return register(new State<>(initial));
  }

  protected final <T> Prop<T> prop() {
    return register(new Prop<>(null));
  }

  protected final <T> Prop<T> prop(T fallback) {
    return register(new Prop<>(fallback));
  }

  protected final <T> Query<T> query(Loader<T> loader) {
    return register(new Query<>(loader));
  }

  /** Runs after mount and whenever a State it read changes; returns its cleanup or null. */
  protected final void effect(Supplier<Runnable> body) {
    register(new Effect(body));
  }

  /** Path parameter of the current route, e.g. "id" for /users/{id}. */
  protected final String pathParam(String name) {
    Session session = scope != null ? scope.session : Session.current();
    if (session == null) {
      throw new IllegalStateException("pathParam() needs a mounted component");
    }
    return session.pathParams.get(name);
  }

  private <P extends Primitive> P register(P primitive) {
    if (scope == null) {
      primitives.add(primitive);
      return primitive;
    }
    if (!scope.rendering) {
      throw new IllegalStateException("primitives are created in field initializers or render(), not in handlers");
    }
    scope.bindPrimitive(primitive, renderIndex++);
    return primitive;
  }

  /** Called by the renderer with this component's scope installed as observer. */
  final Tag<?> runRender() {
    renderIndex = primitives.size();
    Tag<?> root = render();
    if (scope.settledCount < 0) {
      scope.settledCount = scope.cells.size();
    } else if (renderIndex != scope.settledCount) {
      throw new IllegalStateException(getClass().getName()
        + " created " + renderIndex + " primitives this render but " + scope.settledCount
        + " before; primitives must not be created conditionally (ADR 0019)");
    }
    if (root == null) {
      throw new IllegalStateException(getClass().getName() + ".render() must return one root element");
    }
    return root;
  }
}
