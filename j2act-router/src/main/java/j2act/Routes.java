package j2act;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Route DSL home (ADR 0005). Path and page ride positionally; everything else nests:
 *
 * <pre>{@code
 * routes(
 *   page("/", HomePage.class),
 *   scope("/admin",
 *     middleware(Auth.class),
 *     layout(AdminLayout.class,
 *       page("/", UsersPage.class),
 *       page("/users/{id}", UserPage.class))),
 *   redirect("/old-users", "/admin"),
 *   fallback(NotFoundPage.class))
 * }</pre>
 *
 * Routes match in declaration order; {name} segments bind pathParam("name"). A layout
 * or scope's middleware guards everything inside it. Sibling pages under one layout
 * share its mounted instance across soft navigations (ADR 0011).
 */
public final class Routes {

  private Routes() {
  }

  // ---- tree nodes

  public static Router routes(Route... children) {
    return new Router(Arrays.asList(children));
  }

  public static Route page(String path, Class<? extends LiveComponent> pageClass) {
    return new Route(Kind.PAGE, path, pageClass, null, Collections.emptyList());
  }

  /** Groups routes under a path prefix; its middleware guards all of them. */
  public static Route scope(String path, Route... children) {
    return new Route(Kind.SCOPE, path, null, null, Arrays.asList(children));
  }

  /** Wraps the child routes in a layout that renders them via render(DomContent content). */
  public static Route layout(Class<? extends Layout> layoutClass, Route... children) {
    return layout("/", layoutClass, children);
  }

  public static Route layout(String path, Class<? extends Layout> layoutClass, Route... children) {
    return new Route(Kind.LAYOUT, path, layoutClass, null, Arrays.asList(children));
  }

  /** A guard class with a no-arg constructor, applied to its whole group. */
  public static Route middleware(Class<? extends Middleware> guardClass) {
    Middleware guard = instantiate(guardClass);
    return middleware(guard);
  }

  public static Route middleware(Middleware guard) {
    Route route = new Route(Kind.MIDDLEWARE, "/", null, null, Collections.emptyList());
    route.guard = guard;
    return route;
  }

  public static Route redirect(String from, String to) {
    return new Route(Kind.REDIRECT, from, null, to, Collections.emptyList());
  }

  /** The page shown for notFound(), with a 404 status, inside the layouts around it. */
  public static Route fallback(Class<? extends LiveComponent> pageClass) {
    return new Route(Kind.FALLBACK, "/", pageClass, null, Collections.emptyList());
  }

  // ---- throwables (ADR 0015)

  /** Throw from a render, loader or handler: 404 with the fallback page. */
  public static RouteException notFound() {
    return RouteException.notFound();
  }

  /** Throw from a render, loader or handler: 303 on a full load, a soft navigation over the socket. */
  public static RouteException redirect(String to) {
    return RouteException.redirect(to);
  }

  // ---- model

  enum Kind { PAGE, SCOPE, LAYOUT, MIDDLEWARE, REDIRECT, FALLBACK }

  /** A node in the route tree. */
  public static final class Route {
    final Kind kind;
    final String[] segments;
    final Class<?> componentClass;
    final String redirectTo;
    final List<Route> children;
    Middleware guard;

    Route(Kind kind, String path, Class<?> componentClass, String redirectTo, List<Route> children) {
      this.kind = kind;
      this.segments = split(path);
      this.componentClass = componentClass;
      this.redirectTo = redirectTo;
      this.children = new ArrayList<>(children);
    }
  }

  /** A route flattened to its full path, frames and guards. */
  static final class Leaf {
    final String[] segments;
    final List<PageMatch.Frame> frames;
    final List<Middleware> guards;
    final String redirectTo;

    Leaf(String[] segments, List<PageMatch.Frame> frames, List<Middleware> guards, String redirectTo) {
      this.segments = segments;
      this.frames = frames;
      this.guards = guards;
      this.redirectTo = redirectTo;
    }

    PageMatch match(String[] requested) {
      if (requested.length != segments.length) {
        return null;
      }
      Map<String, String> params = new LinkedHashMap<>();
      for (int i = 0; i < segments.length; i++) {
        String segment = segments[i];
        if (segment.startsWith("{") && segment.endsWith("}")) {
          params.put(segment.substring(1, segment.length() - 1), requested[i]);
        } else if (!segment.equals(requested[i])) {
          return null;
        }
      }
      return redirectTo != null ? PageMatch.redirectTo(redirectTo) : PageMatch.of(frames, params, guards);
    }
  }

  /** Ordered leaves; first match wins. */
  public static final class Router implements PageResolver {
    private final List<Leaf> leaves = new ArrayList<>();
    private PageMatch fallback;

    Router(List<Route> roots) {
      flatten(roots, new String[0], new ArrayList<>(), new ArrayList<>(), "r");
    }

    private void flatten(List<Route> nodes, String[] prefix, List<PageMatch.Frame> frames,
                         List<Middleware> inheritedGuards, String id) {
      List<Middleware> guards = new ArrayList<>(inheritedGuards);
      for (Route node : nodes) {
        if (node.kind == Kind.MIDDLEWARE) {
          guards.add(node.guard);
        }
      }
      for (int i = 0; i < nodes.size(); i++) {
        Route node = nodes.get(i);
        String nodeId = id + "/" + i;
        String[] path = concat(prefix, node.segments);
        switch (node.kind) {
          case PAGE: {
            List<PageMatch.Frame> chain = new ArrayList<>(frames);
            chain.add(new PageMatch.Frame(nodeId, supplier(node.componentClass)));
            leaves.add(new Leaf(path, chain, guards, null));
            break;
          }
          case REDIRECT:
            leaves.add(new Leaf(path, frames, guards, node.redirectTo));
            break;
          case SCOPE:
            flatten(node.children, path, frames, guards, nodeId);
            break;
          case LAYOUT: {
            List<PageMatch.Frame> chain = new ArrayList<>(frames);
            chain.add(new PageMatch.Frame(nodeId, supplier(node.componentClass)));
            flatten(node.children, path, chain, guards, nodeId);
            break;
          }
          case FALLBACK:
            if (fallback == null) {
              List<PageMatch.Frame> chain = new ArrayList<>(frames);
              chain.add(new PageMatch.Frame(nodeId, supplier(node.componentClass)));
              fallback = PageMatch.fallback(chain);
            }
            break;
          default:
            break;
        }
      }
    }

    @Override public PageMatch resolve(String path) {
      String[] requested = split(path);
      for (Leaf leaf : leaves) {
        PageMatch match = leaf.match(requested);
        if (match != null) {
          return match;
        }
      }
      return null;
    }

    @Override public PageMatch fallback() {
      return fallback;
    }
  }

  // ---- helpers

  private static Supplier<ComponentTag> supplier(Class<?> type) {
    Constructor<?> constructor;
    try {
      constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(type.getName() + " needs a no-arg constructor", e);
    }
    return () -> {
      try {
        return (ComponentTag) constructor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot create " + type.getName(), e);
      }
    };
  }

  private static <T> T instantiate(Class<T> type) {
    try {
      Constructor<T> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException(type.getName() + " needs a no-arg constructor", e);
    }
  }

  private static String[] concat(String[] a, String[] b) {
    String[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static String[] split(String path) {
    String trimmed = path == null ? "" : path.replaceAll("^/+|/+$", "");
    return trimmed.isEmpty() ? new String[0] : trimmed.split("/+");
  }
}
