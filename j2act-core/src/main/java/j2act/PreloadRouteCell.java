package j2act;

/**
 * The route a preloaded subtree reads while it waits for the click: the target URL and
 * params. Once the navigation adopts the subtree, it derives from the tab's route cell,
 * so the reads recorded against it keep refetching on later param changes.
 */
final class PreloadRouteCell extends Node {

  PreloadRouteCell(Tab tab, RouteInfo target) {
    super(tab, "route", target);
  }

  /** Lane-only. From now on, derive from the tab's route. */
  void follow() {
    body = tab.routeCell::read;
    invalidate();
  }

  void unfollow() {
    if (body != null) {
      body = null;
      tab.routeCell.unsubscribe(this);
    }
  }
}
