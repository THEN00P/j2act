package j2act;

/**
 * A tab's own copy of a browser store's cell it read (ADR 0030, 0031). The cell lives on the browser's
 * lane and the reader on its tab's, so the tab keeps a node of its own, which a change on the browser's
 * lane reaches as a write here: what it reloads holds the update in this tab like any write, and each
 * tab of the browser stays consistent on its own.
 */
final class MirrorCell extends Node {

  final Node source;
  private final Link link = new Link();
  private volatile Throwable sourceFailure;
  private volatile boolean sourceEmpty;
  private volatile boolean sourceFailed;

  MirrorCell(Tab reader, Node source) {
    super(reader, "mirror:" + source.address, source.snapshotValue());
    this.source = source;
    takeState();
  }

  private void takeState() {
    sourceFailure = source.snapshotError();
    sourceEmpty = source.snapshotEmpty();
    sourceFailed = source.snapshotFailed();
  }

  @Override Object read() {
    if (sourceFailure != null) {
      onRead(peek());
      throw new LoadFailed(this, sourceFailure);
    }
    if (sourceEmpty) {
      onRead(peek());
      throw new NotReady(this);
    }
    return super.read();
  }

  @Override boolean readFailed() {
    onRead(peek());
    return sourceFailed;
  }

  /** A reset at this tab's boundary: the load is the browser's, on its lane. */
  @Override void retry() {
    post(source.tab, source::retry);
  }

  /** Subscribes on the browser's lane; a change between the read and the subscription still reaches it. */
  void subscribe() {
    post(source.tab, () -> {
      if (source.disposed || disposed) {
        return;
      }
      source.observers.add(link);
      link.invalidate();
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

  @Override public void invalidate() {
    // Nothing above a mirror is a node of this tab; only its link tells it anything.
  }

  @Override void dispose() {
    unlink();
    super.dispose();
  }

  /** On the browser's lane: tells the tab, on its own lane, what the value is now. */
  private final class Link implements Observer {
    @Override public void invalidate() {
      Object now = source.snapshotValue();
      Throwable failure = source.snapshotError();
      boolean empty = source.snapshotEmpty();
      boolean failed = source.snapshotFailed();
      post(tab, () -> {
        if (disposed) {
          return;
        }
        boolean stateChanged = sourceFailure != failure || sourceEmpty != empty || sourceFailed != failed;
        sourceFailure = failure;
        sourceEmpty = empty;
        sourceFailed = failed;
        if (stateChanged) {
          setOnLane(now);
          notifyObservers();
          // The load landed on the browser's lane; a response waiting on this tab has to hear of it.
          tab.settled();
        } else {
          writeNow(now);
        }
      });
    }

    @Override public void dependsOn(Cell cell) {
    }
  }
}
