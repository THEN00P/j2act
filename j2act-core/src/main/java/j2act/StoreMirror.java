package j2act;

/**
 * A store of another graph, as this one reads it (ADR 0037): a store of this graph whose answer is what the other graph
 * last published, merged into what it had, so a reader here runs again only for what changed. It lives while a
 * computation here holds it, counted by the cleanups of the computations that read it.
 */
final class StoreMirror extends StoreCell implements Mirrors.Mirror {

  final StoreCell source;
  private final Feed feed;
  private final Runnable released;
  /** The computations here whose last run read it. Lane-only. */
  private int holds;

  static StoreMirror of(Tab tab, StoreCell source, Published.State first, Runnable released) {
    ReactiveHost from = source.tab.host;
    Feed feed = new Feed(tab.host, () -> from.job(() -> Tracking.in(source.tab, null, () -> {
      if (source.deriveNode() != null) {
        from.rt.refresh(source.deriveNode());
      }
      return null;
    })));
    Object seed = first != null && first.value != null ? first.value : tab.host.stores().emptyLike(source.view);
    if (first != null) {
      feed.accept(first);
    }
    return new StoreMirror(tab, source, feed, seed, released);
  }

  private StoreMirror(Tab tab, StoreCell source, Feed feed, Object seed, Runnable released) {
    super(tab, "mirror:" + source.address, (j2act.internal.signals.Stores.Derive) draft -> feed.read(), seed,
      source.optimistic());
    this.source = source;
    this.feed = feed;
    this.released = released;
  }

  @Override public Cell source() {
    return source;
  }

  @Override public void accept(Published.State next) {
    feed.accept(next);
  }

  @Override public Feed feed() {
    return feed;
  }

  /** Lane-only. A read here: what reads it holds it until its next run or its end. */
  void held() {
    tab.host.mirrors.asked(feed);
    if (tab.rt.getObserver() == null) {
      return;
    }
    holds++;
    tab.rt.onCleanup(() -> {
      if (--holds == 0) {
        tab.host.later(this::releaseIfUnheld);
      }
    });
  }

  /** Lane-only, at the end of a job: nothing read it again since its last holder let go. */
  void releaseIfUnheld() {
    if (holds == 0 && !disposed) {
      released.run();
    }
  }
}
