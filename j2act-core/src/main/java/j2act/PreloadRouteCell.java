package j2act;

/**
 * The route a preloaded subtree reads while it waits for the click: the target URL and params. Once the
 * navigation adopts the subtree it derives from the tab's route, so what read it follows later changes.
 */
final class PreloadRouteCell extends ValueCell {

  private final ValueCell following;

  PreloadRouteCell(Tab tab, RouteInfo target) {
    this(tab, target, new ValueCell(tab, "route:following", false, true));
  }

  private PreloadRouteCell(Tab tab, RouteInfo target, ValueCell following) {
    super(tab, "route", () -> (Boolean) following.read() ? tab.routeCell.read() : target);
    this.following = following;
  }

  /** Lane-only. From now on, derive from the tab's route. */
  void follow() {
    following.write(true);
  }

  void unfollow() {
    dispose();
  }
}
