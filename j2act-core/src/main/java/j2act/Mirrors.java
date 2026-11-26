package j2act;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values and stores of other graphs this graph reads (ADR 0037), one mirror each. The first read subscribes; the
 * source answers with its state now and sends every later revision, which this graph applies in one job of its own. A
 * mirror nothing here reads any more unsubscribes. Lane-only, the host's.
 */
final class Mirrors {

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
    return host.rt.read(mirror.node);
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

  /** Lane-only. This graph's copy of the store, subscribed on first use. */
  StoreMirror storeOf(StoreCell source) {
    boolean fresh = !cells.containsKey(source);
    StoreMirror mirror = mirror(source, first -> StoreMirror.of(host.tab, source, first, () -> release(source)));
    if (fresh) {
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
      from.hop(() -> first[0] = from.published().subscribe(source, host));
      M made = made(make, first[0]);
      cells.put(source, made);
      return made;
    }
    // A graph of stores waits for no older lane: it reads the value as pending until the answer comes.
    M made = made(make, null);
    cells.put(source, made);
    from.job(() -> {
      Published.State first = from.published().subscribe(source, host);
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

  /** Lane-only. One revision of another graph: every value it changed that this graph reads, in one job. */
  void apply(ReactiveHost from, long revision, List<Published.Update> updates) {
    if (awaiting.containsKey(from)) {
      deferred.computeIfAbsent(from, h -> new java.util.ArrayList<>()).add(updates);
      return;
    }
    applyNow(updates);
  }

  private void applyNow(List<Published.Update> updates) {
    for (Published.Update update : updates) {
      Mirror mirror = cells.get(update.cell);
      if (mirror != null) {
        mirror.accept(update.state);
      }
    }
  }

  /** Nothing reads the mirror any more: the source stops sending it. */
  private void release(Cell source) {
    Mirror mirror = cells.remove(source);
    if (mirror == null) {
      return;
    }
    ReactiveHost from = source.tab.host;
    from.job(() -> from.published().unsubscribe(source, host));
    // Not while the engine is unlinking it: at the end of this job.
    host.later(() -> ((Cell) mirror).dispose());
  }

  /** Lane-only. This graph ended: every source it read stops sending. */
  void releaseAll() {
    java.util.Set<ReactiveHost> sources = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (Mirror mirror : cells.values()) {
      sources.add(mirror.source().tab.host);
    }
    cells.clear();
    for (ReactiveHost from : sources) {
      from.job(() -> from.published().forget(host));
    }
  }
}
