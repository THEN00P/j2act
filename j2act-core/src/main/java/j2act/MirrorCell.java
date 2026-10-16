package j2act;

import java.util.Objects;

/**
 * A tab's stand-in for a browser store's cell it read (ADR 0030). The cell lives on the browser's
 * lane, the reader on its tab's: the mirror carries the read in the tab's own graph, and a link
 * on the browser's cell invalidates it from there. Its value is always the source's.
 */
final class MirrorCell extends Cell {

  final Cell source;
  private final Link link = new Link();

  MirrorCell(Tab reader, Cell source) {
    super(reader, "mirror:" + source.address);
    this.source = source;
  }

  @Override Object peek() {
    return source.peek();
  }

  /** Subscribes on the browser's lane; a change between the read and the subscription still invalidates. */
  void subscribe(Object seen) {
    source.tab.post(() -> {
      if (source.disposed || disposed) {
        return;
      }
      source.observers.add(link);
      if (!Objects.equals(source.peek(), seen)) {
        link.invalidate();
      }
    });
  }

  @Override void onUnobserved() {
    tab.forgetMirror(this);
    unlink();
  }

  void unlink() {
    post(source.tab, () -> source.unsubscribe(link));
  }

  /** Posts to another lane; once j2act is closing and its pool refuses work, there is nothing left to tell. */
  private static void post(Tab to, Runnable task) {
    try {
      to.post(task);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // closing
    }
  }

  @Override void dispose() {
    unlink();
    super.dispose();
  }

  /** On the browser's lane: tells the tab, on its own lane, that the value changed. */
  private final class Link implements Observer {
    @Override public void invalidate() {
      post(tab, () -> {
        if (!disposed) {
          notifyObservers();
        }
      });
    }

    @Override public void dependsOn(Cell cell) {
    }
  }
}
