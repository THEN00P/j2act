package j2act;

/**
 * The route one route frame and what it places read, as each route in Solid Router has a context of its own. It follows
 * the route above it until it stops at one URL: a frame being left stops at the URL it shows, since it stays on screen
 * until what replaces it comes, and a preloaded subtree starts stopped at its target until the click adopts it.
 */
final class FrameRouteCell extends ValueCell {

  private final ValueCell stoppedAt;

  /** With a route, it starts stopped there. */
  FrameRouteCell(Tab tab, ValueCell above, RouteInfo stopped) {
    this(tab, above, new ValueCell(tab, "route:stoppedAt", stopped, true));
  }

  private FrameRouteCell(Tab tab, ValueCell above, ValueCell stoppedAt) {
    super(tab, "route", () -> {
      Object stopped = stoppedAt.read();
      return stopped != null ? stopped : above.read();
    });
    this.stoppedAt = stoppedAt;
  }

  /** Lane-only. From now on, this URL. */
  void stop(RouteInfo route) {
    stoppedAt.write(route);
  }

  /** Lane-only. From now on, derive from the route above. */
  void follow() {
    stoppedAt.write(null);
  }
}
