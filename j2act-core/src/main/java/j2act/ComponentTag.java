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

  /**
   * Override to make this component a loading boundary (ADR 0007): shown instead of
   * render() while a query created in its subtree has no data yet. Nearest boundary wins.
   */
  protected Tag<?> loading() {
    return null;
  }

  /** Override to make this component an error boundary: shown when a query in its subtree failed with no data. */
  protected Tag<?> error(PageError error) {
    return null;
  }

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

  /** A pure derived value, recomputed when what it read changes (CONTEXT: Computed). */
  protected final <T> Computed<T> computed(java.util.function.Supplier<T> body) {
    return register(new Computed<>(body));
  }

  /** An async write: status, data, error and variables are tracked (ADR 0006). */
  protected final <V, R> Mutation<V, R> mutation(Mutation.Body<V, R> body) {
    return register(new Mutation<>(body));
  }

  /**
   * A Mutation whose body streams a file to the browser (ADR 0012), e.g.
   * download((String like, OutputStream out) -> csv.write(like, out)).withFileName("users.csv").
   */
  protected final <V> Download<V> download(Download.Writer<V> writer) {
    return register(new Download<>(writer));
  }

  /** A Mutation that receives a file in resumable chunks, with server-enforced restrictions (ADR 0006). */
  protected final Upload upload() {
    return register(new Upload());
  }

  /** Refetches every keyed query whose key starts with these parts, in this session (ADR 0020). */
  protected final void invalidate(Object... keyPrefix) {
    Session session = scope != null ? scope.session : Session.current();
    if (session == null) {
      throw new IllegalStateException("invalidate() needs a mounted component");
    }
    session.invalidate(java.util.Arrays.asList(keyPrefix));
  }

  /** Runs after mount and whenever a State it read changes; returns its cleanup or null. */
  protected final void effect(Supplier<Runnable> body) {
    register(new Effect(body));
  }

  /** Path parameter of the current route, e.g. "id" for /users/{id}. Tracked: a change re-renders or refetches. */
  protected final String pathParam(String name) {
    return route().params.get(name);
  }

  /** Query parameter of the current URL, or null. Tracked like pathParam. */
  protected final String queryParam(String name) {
    return route().queryParams.get(name);
  }

  /** The session's identity (ADR 0004). Tracked: render branches re-run when it changes. */
  protected final AuthCtx auth() {
    return session("auth()").readAuth();
  }

  /** Soft-navigates to an app path after the current event, like a link click (ADR 0011). */
  protected final void navigate(String path) {
    Session session = session("navigate()");
    session.post(() -> session.navigate(path, "push"));
  }

  private RouteInfo route() {
    return (RouteInfo) session("pathParam()").routeCell.read();
  }

  private Session session(String what) {
    Session session = scope != null ? scope.session : Session.current();
    if (session == null) {
      throw new IllegalStateException(what + " needs a mounted component");
    }
    return session;
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
