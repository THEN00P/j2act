package j2act;

/**
 * The route a preloaded subtree reads while it waits for the click: the target URL and
 * params. Once the navigation adopts the subtree, it follows the session's route cell,
 * so the reads recorded against it keep refetching on later param changes.
 */
final class PreloadRouteCell extends ValueCell implements Observer {

  private boolean following;

  PreloadRouteCell(Session session, RouteInfo target) {
    super(session, "route", target);
  }

  /** Lane-only. From now on, mirror the session's route. */
  void follow() {
    following = true;
    session.routeCell.observers.add(this);
    setOnLane(session.routeCell.peek());
  }

  void unfollow() {
    if (following) {
      following = false;
      session.routeCell.unsubscribe(this);
    }
  }

  @Override public void invalidate() {
    setOnLane(session.routeCell.peek());
  }

  @Override public void dependsOn(Cell cell) {
    // follows exactly one cell, subscribed in follow()
  }
}
