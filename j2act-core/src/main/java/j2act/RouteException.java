package j2act;

/**
 * Thrown from a render, query loader or handler to end the current route: a 404 or
 * 303 on a full load, a soft navigation over the socket (ADR 0015). Stackless: it is
 * control flow, not an error.
 */
public final class RouteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  final String redirect;

  private RouteException(String redirect) {
    super(redirect == null ? "not found" : "redirect to " + redirect, null, false, false);
    this.redirect = redirect;
  }

  public static RouteException notFound() {
    return new RouteException(null);
  }

  /** App-relative path such as "/login". */
  public static RouteException redirect(String path) {
    return new RouteException(path);
  }

  public boolean isNotFound() {
    return redirect == null;
  }
}
