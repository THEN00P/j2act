package j2act;

/**
 * A value of another graph, as this one reads it (ADR 0037): a memo of this graph that shows the state the other graph
 * last published, so what reads it runs again here, in this graph's own flush, when a revision changes it. While the
 * other graph's value loads, this one is pending too, and this graph's own boundaries decide what waits for it.
 */
final class MirrorCell extends ValueCell implements Mirrors.Mirror {

  final ValueCell source;
  private final Feed feed;

  /** The mirror, with the first state the source answered, or null while the answer is on its way. */
  static MirrorCell of(Tab tab, ValueCell source, Published.State first, Runnable unobserved) {
    ReactiveHost from = source.tab.host;
    Feed feed = new Feed(tab.host, () -> from.job(() -> Tracking.in(source.tab, null, () -> from.rt.refresh(source.node))));
    if (first != null) {
      feed.accept(first);
    }
    return new MirrorCell(tab, source, feed, unobserved);
  }

  private MirrorCell(Tab tab, ValueCell source, Feed feed, Runnable unobserved) {
    super(tab, "mirror:" + source.address, cell -> prev -> feed.read(),
      new j2act.internal.signals.Runtime.Options<Object>().equals((a, b) -> a == b).unobserved(unobserved));
    this.source = source;
    this.feed = feed;
  }

  @Override public Cell source() {
    return source;
  }

  @Override boolean isAsync() {
    return true;
  }

  @Override public void accept(Published.State next) {
    feed.accept(next);
  }

  @Override public Feed feed() {
    return feed;
  }

  @Override boolean readFailed() {
    return feed.readFailed();
  }

  @Override boolean committedFailed() {
    return feed.failed();
  }

  @Override void dispose() {
    super.dispose();
    rt.disposeNode(node);
  }
}
