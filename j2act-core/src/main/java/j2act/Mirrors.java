package j2act;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values and stores of other graphs this graph reads (ADR 0037), one mirror each. The first read subscribes; the
 * source answers with its state now and sends every later revision, which this graph applies in one job of its own. A
 * mirror nothing here reads any more unsubscribes. Lane-only, the host's.
 */
final class Mirrors implements Published.Reader {

  /** What this graph holds of another graph's value or store. */
  interface Mirror {
    Cell source();

    /** A state the source published. */
    void accept(Published.State state);

    /** What it shows of the source. */
    Feed feed();
  }

  private final ReactiveHost host;
  private final Map<Cell, Mirror> cells = new IdentityHashMap<>();

  /** Nobody looks at this graph's page (ADR 0037): sources skip it, and it catches up when someone does. */
  private volatile boolean paused;
  /** Lane-only. The page is hidden or has no socket. */
  private boolean hidden;
  /** Lane-only. Components that keep this graph getting changes while hidden (liveWhileHidden()). */
  private int keptLive;
  /** Lane-only. Clicks waiting to catch up: they need the revisions now, hidden or not. */
  private int waking;

  @Override public boolean paused() {
    return paused;
  }

  /** Lane-only. Whether anyone looks at this graph's page. */
  void hidden(boolean hidden) {
    this.hidden = hidden;
    repause();
  }

  /** Lane-only. A component keeps this graph live while hidden (+1), or left (-1). */
  void keepLive(int delta) {
    keptLive += delta;
    repause();
  }

  private void repause() {
    boolean next = hidden && keptLive == 0 && waking == 0;
    if (next == paused) {
      return;
    }
    // Before the sources hear of it, so what they publish meanwhile reaches this graph or the catch-up does.
    paused = next;
    for (ReactiveHost from : sources()) {
      if (next) {
        from.job(() -> from.published().paused(this));
      } else {
        from.job(() -> {
          List<Published.Update> now = from.published().resumed(this);
          if (!now.isEmpty()) {
            deliver(from, from.published().revision, now);
          }
        });
      }
    }
  }

  private java.util.Set<ReactiveHost> sources() {
    java.util.Set<ReactiveHost> sources = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (Mirror mirror : cells.values()) {
      sources.add(mirror.source().tab.host);
    }
    return sources;
  }

  Mirrors(ReactiveHost host) {
    this.host = host;
  }

  /** Lane-only. A read of another graph's value from code running here: tracked if something here tracks. */
  Object read(ValueCell source) {
    if (host.rt.getObserver() == null) {
      return readUntracked(source);
    }
    MirrorCell mirror = of(source);
    if (asking != null) {
      asking.add(mirror.feed());
    }
    Object value = host.rt.read(mirror.node);
    Scope scope = Tracking.scope();
    if (scope != null) {
      Scope.noteSharedRead(scope, new SharedRead(host, source, null, j2act.internal.store.JavaShape.Reads.VALUE,
        mirror.feed().value(), false));
    }
    return value;
  }

  /** Lane-only. What this graph shows of the value, or the source's own when nothing here reads it. */
  Object readUntracked(ValueCell source) {
    Mirror mirror = cells.get(source);
    if (mirror != null) {
      MirrorCell cell = (MirrorCell) mirror;
      if (asking != null) {
        asking.add(cell.feed());
      }
      return host.rt.untrack(() -> host.rt.read(cell.node));
    }
    ReactiveHost from = source.tab.host;
    Object[] value = new Object[1];
    RuntimeException[] failure = new RuntimeException[1];
    Runnable read = () -> {
      try {
        value[0] = from.rt.untrack(() -> from.rt.read(source.node));
      } catch (RuntimeException e) {
        failure[0] = e;
      }
    };
    if (host.mayInline(from)) {
      from.hop(read);
    } else if (!from.tryHop(read)) {
      // Its lane is busy, and waiting for it could wait on this one: what it committed will do.
      return from.rt.committed(source.node);
    }
    if (failure[0] != null) {
      throw failure[0];
    }
    return value[0];
  }

  /** Lane-only. This graph's mirror of the value, subscribed on first use. */
  MirrorCell of(ValueCell source) {
    return mirror(source, first -> MirrorCell.of(host.tab, source, first, () -> host.later(() -> {
      // The engine lets go of a reader while it runs it in another lane too: only one nobody read since goes.
      Mirror now = cells.get(source);
      if (now instanceof MirrorCell && !host.rt.observed(((MirrorCell) now).node)) {
        release(source);
      }
    })));
  }

  /** The copies of other graphs' stores here, by their root target, for what reads them. */
  private final Map<j2act.internal.signals.StoreTarget, StoreMirror> byRoot = new IdentityHashMap<>();

  /** Lane-only. A tracked read of a store here: a copy's part, which the scope rendering notes with what it saw. */
  void storeRead(j2act.internal.signals.StoreTarget target, Object key, int kind) {
    StoreMirror mirror = byRoot.get(target.root());
    Scope scope = mirror == null ? null : Tracking.scope();
    if (scope == null) {
      return;
    }
    java.util.ArrayList<Object> keys = new java.util.ArrayList<>();
    keys.add(key);
    boolean inElement = false;
    for (j2act.internal.signals.StoreTarget t = target; t.parent() != null; t = t.parent()) {
      keys.add(t.keyInParent());
      Object parentView = t.parent().view();
      inElement |= parentView instanceof java.util.List || parentView instanceof java.util.Map;
    }
    java.util.Collections.reverse(keys);
    Scope.noteSharedRead(scope, new SharedRead(host, mirror.source, keys.toArray(), kind, mirror.feed().value(),
      inElement));
  }

  /** Lane-only. This graph's copy of the store, subscribed on first use. */
  StoreMirror storeOf(StoreCell source) {
    boolean fresh = !cells.containsKey(source);
    StoreMirror mirror = mirror(source, first -> StoreMirror.of(host.tab, source, first, () -> release(source)));
    if (fresh) {
      byRoot.put(mirror.shape.targetOf(mirror.view), mirror);
      // One that only untracked code read goes again at the end of the job.
      host.later(mirror::releaseIfUnheld);
    }
    return mirror;
  }

  @SuppressWarnings("unchecked")
  private <M extends Mirror> M mirror(Cell source, java.util.function.Function<Published.State, M> make) {
    Mirror existing = cells.get(source);
    if (existing != null) {
      return (M) existing;
    }
    ReactiveHost from = source.tab.host;
    if (host.mayInline(from)) {
      Published.State[] first = new Published.State[1];
      from.hop(() -> first[0] = from.published().subscribe(source, this));
      M made = made(make, first[0]);
      cells.put(source, made);
      return made;
    }
    // A graph of stores waits for no older lane: it reads the value as pending until the answer comes.
    M made = made(make, null);
    cells.put(source, made);
    from.job(() -> {
      Published.State first = from.published().subscribe(source, this);
      host.job(() -> {
        if (cells.get(source) == made) {
          made.accept(first);
        }
      });
    });
    return made;
  }

  /**
   * A mirror is often made inside a page's render, by its first read: made outside it, with nothing tracking, so what
   * its node reads is its own and the page subscribes to the mirror, not to what is inside it.
   */
  private <M extends Mirror> M made(java.util.function.Function<Published.State, M> make, Published.State first) {
    return host.rt.runWithOwner(null, () -> host.rt.untrack(() -> make.apply(first)));
  }

  /** While isPending(...) runs here: the mirrors what it asks about reads. Lane-only. */
  private java.util.List<Feed> asking;

  /**
   * Lane-only. isPending(fn) here: the engine's answer, or an action here sending writes to what fn reads of another
   * graph. Every such mirror is asked, so what asks runs again when any of them changes.
   */
  boolean isPending(java.util.function.Supplier<?> fn) {
    java.util.List<Feed> outer = asking;
    java.util.List<Feed> seen = new java.util.ArrayList<>();
    asking = seen;
    boolean pending;
    try {
      pending = host.rt.isPending(fn);
    } finally {
      asking = outer;
    }
    for (Feed feed : seen) {
      pending |= feed.readOutbound();
    }
    if (outer != null) {
      outer.addAll(seen);
    }
    return pending;
  }

  /** Lane-only. A store's copy was read: an isPending(...) running here asks about it. */
  void asked(Feed feed) {
    if (asking != null) {
      asking.add(feed);
    }
  }

  /** Lane-only. The mirror if this graph has one. */
  Mirror existing(Cell source) {
    return cells.get(source);
  }

  /**
   * Graphs an action here sent writes to and has not heard back from, with the revisions they sent meanwhile: those go
   * into the action's own transaction when it hears back, so the page shows what it wrote when the action ends, in
   * one change, as for its own writes.
   */
  private final Map<ReactiveHost, Integer> awaiting = new IdentityHashMap<>();
  private final Map<ReactiveHost, java.util.List<java.util.List<Published.Update>>> deferred = new IdentityHashMap<>();

  /** Lane-only. An action's writes went to from. */
  void await(ReactiveHost from) {
    awaiting.merge(from, 1, Integer::sum);
  }

  /** Lane-only. from applied an action's writes and published them: what it sent since, to apply in the action. */
  Runnable heard(ReactiveHost from) {
    awaiting.computeIfPresent(from, (h, n) -> n > 1 ? n - 1 : null);
    java.util.List<java.util.List<Published.Update>> held = awaiting.containsKey(from) ? deferred.get(from)
      : deferred.remove(from);
    if (held == null) {
      return () -> { };
    }
    java.util.List<java.util.List<Published.Update>> now = new java.util.ArrayList<>(held);
    held.clear();
    return () -> {
      for (java.util.List<Published.Update> updates : now) {
        applyNow(updates);
      }
    };
  }

  /** Revisions that came from other graphs and wait for this lane, in order. */
  private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> arriving = new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Any thread. A revision of another graph for this one. What arrives before this lane gets to it is one job, so a
   * page whose lane fell behind renders once with the latest state, not once for every revision in between.
   */
  @Override public void deliver(ReactiveHost from, long revision, List<Published.Update> updates) {
    arriving.add(new Object[] {from, revision, updates});
    if (draining.compareAndSet(false, true)) {
      host.job(this::drainArriving);
    }
  }

  @SuppressWarnings("unchecked")
  private void drainArriving() {
    draining.set(false);
    Object[] next;
    while ((next = arriving.poll()) != null) {
      apply((ReactiveHost) next[0], (Long) next[1], (List<Published.Update>) next[2]);
    }
  }

  /** Lane-only. One revision of another graph: every value it changed that this graph reads, in one job. */
  void apply(ReactiveHost from, long revision, List<Published.Update> updates) {
    if (awaiting.containsKey(from)) {
      deferred.computeIfAbsent(from, h -> new java.util.ArrayList<>()).add(updates);
      return;
    }
    applyNow(updates);
  }

  /** Waiting for revisions, each with what runs once this graph has them. Lane-only. */
  private final java.util.List<java.util.Map.Entry<Map<Cell, Long>, Runnable>> catchingUp = new java.util.ArrayList<>();

  /**
   * Lane-only. Runs then in a job of its own once this graph has the revisions given, after the job that applied them
   * rendered what they changed; a value this graph no longer reads counts as had.
   */
  void whenCaughtUp(Map<Cell, Long> revisions, Runnable then) {
    if (caughtUp(revisions)) {
      host.job(then);
      return;
    }
    // A hidden page catches up for the click, then goes back to waiting until someone looks.
    waking++;
    repause();
    catchingUp.add(new java.util.AbstractMap.SimpleImmutableEntry<>(revisions, () -> {
      waking--;
      repause();
      then.run();
    }));
  }

  private boolean caughtUp(Map<Cell, Long> revisions) {
    for (Map.Entry<Cell, Long> e : revisions.entrySet()) {
      Mirror mirror = cells.get(e.getKey());
      if (mirror != null && mirror.feed().revision() < e.getValue()) {
        return false;
      }
    }
    return true;
  }

  private void applyNow(List<Published.Update> updates) {
    for (Published.Update update : updates) {
      Mirror mirror = cells.get(update.cell);
      if (mirror != null) {
        mirror.accept(update.state);
      }
    }
    for (java.util.Iterator<java.util.Map.Entry<Map<Cell, Long>, Runnable>> it = catchingUp.iterator(); it.hasNext(); ) {
      java.util.Map.Entry<Map<Cell, Long>, Runnable> waiting = it.next();
      if (caughtUp(waiting.getKey())) {
        it.remove();
        host.job(waiting.getValue());
      }
    }
  }

  /** Nothing reads the mirror any more: the source stops sending it. */
  private void release(Cell source) {
    Mirror mirror = cells.remove(source);
    if (mirror == null) {
      return;
    }
    if (mirror instanceof StoreMirror) {
      byRoot.remove(((StoreMirror) mirror).shape.targetOf(((StoreMirror) mirror).view));
    }
    ReactiveHost from = source.tab.host;
    from.job(() -> from.published().unsubscribe(source, this));
    // Not while the engine is unlinking it: at the end of this job.
    host.later(() -> ((Cell) mirror).dispose());
  }

  /** Lane-only. This graph ended: every source it read stops sending. */
  void releaseAll() {
    java.util.Set<ReactiveHost> sources = sources();
    cells.clear();
    for (ReactiveHost from : sources) {
      from.job(() -> from.published().forget(this));
    }
  }
}
