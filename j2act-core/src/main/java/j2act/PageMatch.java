package j2act;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A resolved route: the frames to mount (outer layouts first, page last), the path
 * parameters, and the guards on the way. Or a redirect, or the fallback page with a
 * 404 status. The router module builds these; tests can build them by hand.
 */
public final class PageMatch {

  /** One mount level. The id is the route node's identity: same id, same instance on navigation. */
  public static final class Frame {
    final String id;
    final Supplier<? extends ComponentTag> factory;

    public Frame(String id, Supplier<? extends ComponentTag> factory) {
      this.id = id;
      this.factory = factory;
    }
  }

  final List<Frame> frames;
  final Map<String, String> params;
  final List<Middleware> guards;
  final String redirect;
  final int status;

  private PageMatch(List<Frame> frames, Map<String, String> params, List<Middleware> guards, String redirect, int status) {
    this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
    this.params = Collections.unmodifiableMap(params);
    this.guards = Collections.unmodifiableList(new ArrayList<>(guards));
    this.redirect = redirect;
    this.status = status;
  }

  /** A single page with no layouts or guards. */
  public PageMatch(Supplier<? extends LiveComponent> page, Map<String, String> params) {
    this(Collections.singletonList(new Frame("page", page)), params, Collections.emptyList(), null, 200);
  }

  public static PageMatch of(List<Frame> frames, Map<String, String> params, List<Middleware> guards) {
    return new PageMatch(frames, params, guards, null, 200);
  }

  /** The fallback page: rendered with a 404 status. */
  public static PageMatch fallback(List<Frame> frames) {
    return new PageMatch(frames, Collections.emptyMap(), Collections.emptyList(), null, 404);
  }

  public static PageMatch redirectTo(String path) {
    return new PageMatch(Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), path, 303);
  }
}
