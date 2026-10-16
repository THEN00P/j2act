package j2act;

/**
 * The route a preloaded subtree reads while it waits for the click: the target URL and
 * params. Once the navigation adopts the subtree, it follows the tab's route cell,
 * so the reads recorded against it keep refetching on later param changes.
 */
final class PreloadRouteCell extends ValueCell implements Observer {

  private boolean following;

  PreloadRouteCell(Tab tab, RouteInfo target) {
    super(tab, "route", target);
  }

  /** Lane-only. From now on, mirror the tab's route. */
  void follow() {
    following = true;
    tab.routeCell.observers.add(this);
    setOnLane(tab.routeCell.peek());
  }

  void unfollow() {
    if (following) {
      following = false;
      tab.routeCell.unsubscribe(this);
    }
  }

  @Override public void invalidate() {
    setOnLane(tab.routeCell.peek());
  }

  @Override public void dependsOn(Cell cell) {
    // follows exactly one cell, subscribed in follow()
  }
}
