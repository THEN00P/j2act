package j2act;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Route DSL home (ADR 0005): routes(page("/", Home.class), page("/users/{id}", User.class)).
 * The first slice supports flat pages with {param} segments; scopes, layouts,
 * middleware, redirect() and fallback() come with soft navigation (ADR 0011).
 */
public final class Routes {

  private Routes() {
  }

  public static Router routes(Route... routes) {
    return new Router(Arrays.asList(routes));
  }

  public static Route page(String path, Class<? extends LiveComponent> pageClass) {
    return new Route(path, pageClass);
  }

  /** A single page route. */
  public static final class Route {
    private final String[] segments;
    private final Constructor<? extends LiveComponent> constructor;

    Route(String path, Class<? extends LiveComponent> pageClass) {
      this.segments = split(path);
      try {
        this.constructor = pageClass.getDeclaredConstructor();
        this.constructor.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new IllegalArgumentException(pageClass.getName() + " needs a no-arg constructor", e);
      }
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
      return new PageMatch(this::instantiate, params);
    }

    private LiveComponent instantiate() {
      try {
        return constructor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot create " + constructor.getDeclaringClass().getName(), e);
      }
    }
  }

  /** Ordered route list; first match wins. */
  public static final class Router implements PageResolver {
    private final List<Route> routes;

    Router(List<Route> routes) {
      this.routes = new ArrayList<>(routes);
    }

    @Override public PageMatch resolve(String path) {
      String[] requested = split(path);
      for (Route route : routes) {
        PageMatch match = route.match(requested);
        if (match != null) {
          return match;
        }
      }
      return null;
    }
  }

  private static String[] split(String path) {
    String trimmed = path == null ? "" : path.replaceAll("^/+|/+$", "");
    return trimmed.isEmpty() ? new String[0] : trimmed.split("/+");
  }
}
