package j2act;

import j2act.internal.signals.Node;
import j2act.internal.signals.Runtime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What other graphs read of one host's values (ADR 0037). Each value some other graph reads has an entry: who reads
 * it, the state it was last sent in, and an effect that keeps it computed. After every job of the host, the values
 * whose state changed go out together as one revision, numbered, to each graph that reads one of them.
 * Lane-only, the host's.
 */
final class Published {

  /** One value's state as another graph sees it: what a store method finished, never what it is still doing. */
  static final class State {
    final boolean hasValue;
    final Object value;
    final boolean pending;
    final Throwable error;
    /** withStaleOnError: the last load failed, and the value is the old one. */
    final boolean failed;
    final long revision;
    /** The graph whose batch of writes made this state, or null for any other change. */
    final ReactiveHost author;
    /** The state before this one, a few back, for a page that wrote this one itself (ADR 0037). */
    final State before;

    State(boolean hasValue, Object value, boolean pending, Throwable error, boolean failed, long revision) {
      this(hasValue, value, pending, error, failed, revision, null, null);
    }

    private State(boolean hasValue, Object value, boolean pending, Throwable error, boolean failed, long revision,
        ReactiveHost author, State before) {
      this.hasValue = hasValue;
      this.value = value;
      this.pending = pending;
      this.error = error;
      this.failed = failed;
      this.revision = revision;
      this.author = author;
      this.before = before;
    }

    /** This state, made by author's batch after previous, which it keeps a few states deep. */
    State after(State previous, ReactiveHost author) {
      return new State(hasValue, value, pending, error, failed, revision, author, trimmed(previous, 8));
    }

    private static State trimmed(State state, int depth) {
      if (state == null || depth == 0) {
        return null;
      }
      return new State(state.hasValue, state.value, state.pending, state.error, state.failed, state.revision,
        state.author, trimmed(state.before, depth - 1));
    }

    /** The same state, as a reader would see it: the same object, by identity, as the engine compares. */
    boolean sameAs(State other) {
      return other != null && hasValue == other.hasValue && value == other.value && pending == other.pending
        && error == other.error && failed == other.failed;
    }

    State at(long next) {
      return new State(hasValue, value, pending, error, failed, next);
    }
  }

  /** A changed value, for one reader. */
  static final class Update {
    final Cell cell;
    final State state;

    Update(Cell cell, State state) {
      this.cell = cell;
      this.state = state;
    }
  }

  /** What reads a host's values: another graph's mirrors here, or another server (ADR 0037). */
  interface Reader {
    /** A revision of from's: every value it changed that this reader reads. Any thread. */
    void deliver(ReactiveHost from, long revision, List<Update> updates);
  }

  private static final class Entry {
    final Cell cell;
    /**
     * Each reader's mirrors of the value: one subscribes and one unsubscribes, so a mirror made again before the old
     * one's unsubscribe arrived keeps getting revisions.
     */
    final Map<Reader, Integer> readers = new LinkedHashMap<>();
    State last;
    Node tap;

    Entry(Cell cell) {
      this.cell = cell;
    }
  }

  private final ReactiveHost host;
  private final Map<Cell, Entry> entries = new IdentityHashMap<>();
  /** The number of the last revision this host published. */
  long revision;

  Published(ReactiveHost host) {
    this.host = host;
  }

  /**
   * Lane-only. reader reads cell from now on: it gets the state the value is in now, and every revision after that
   * changes it, so nothing that happens in between is lost.
   */
  State subscribe(Cell cell, Reader reader) {
    Entry entry = entries.get(cell);
    if (entry == null) {
      entry = new Entry(cell);
      entries.put(cell, entry);
      entry.tap = tap(cell);
      entry.last = stateOf(cell).at(revision);
      host.lastStates.put(cell, entry.last);
      if (cell instanceof Stub.Stubbed) {
        // A stub's value comes from the server that holds the store, from now on (ADR 0037).
        host.tab.sharedFor.stub.subscribe(cell.address);
      }
    }
    entry.readers.merge(reader, 1, Integer::sum);
    return entry.last;
  }

  /** Lane-only. reader no longer reads cell. */
  void unsubscribe(Cell cell, Reader reader) {
    Entry entry = entries.get(cell);
    if (entry == null) {
      return;
    }
    entry.readers.computeIfPresent(reader, (r, count) -> count > 1 ? count - 1 : null);
    if (entry.readers.isEmpty()) {
      entries.remove(cell);
      host.lastStates.remove(cell);
      unstub(cell);
      if (entry.tap != null) {
        host.rt.disposeNode(entry.tap);
      }
      unreadIfNone();
    }
  }

  private void unstub(Cell cell) {
    if (cell instanceof Stub.Stubbed) {
      host.tab.sharedFor.stub.unsubscribe(cell.address);
    }
  }

  private void unreadIfNone() {
    if (entries.isEmpty() && host.unread != null) {
      host.unread.run();
    }
  }

  /** Lane-only. How many values other graphs read here, for tests. */
  int size() {
    return entries.size();
  }

  /** Lane-only. A reader's graph ended. */
  void forget(Reader reader) {
    for (Entry entry : new ArrayList<>(entries.values())) {
      if (entry.readers.remove(reader) != null && entry.readers.isEmpty()) {
        entries.remove(entry.cell);
        host.lastStates.remove(entry.cell);
        unstub(entry.cell);
        if (entry.tap != null) {
          host.rt.disposeNode(entry.tap);
        }
      }
    }
    unreadIfNone();
  }

  /** Lane-only, at the end of a job: what changed goes out as one revision. */
  void afterJob() {
    if (entries.isEmpty()) {
      return;
    }
    Map<Reader, List<Update>> out = null;
    long next = revision + 1;
    for (Entry entry : entries.values()) {
      if (entry.cell.disposed) {
        continue;
      }
      State now = stateOf(entry.cell);
      if (now.sameAs(entry.last)) {
        continue;
      }
      entry.last = now.at(next).after(entry.last, host.writing);
      host.lastStates.put(entry.cell, entry.last);
      if (out == null) {
        out = new LinkedHashMap<>();
      }
      for (Reader reader : entry.readers.keySet()) {
        out.computeIfAbsent(reader, r -> new ArrayList<>()).add(new Update(entry.cell, entry.last));
      }
    }
    if (out == null) {
      return;
    }
    revision = next;
    for (Map.Entry<Reader, List<Update>> e : out.entrySet()) {
      e.getKey().deliver(host, next, e.getValue());
    }
  }

  /**
   * An effect that reads the value, so that the engine keeps it computed while nothing here reads it: an effect does
   * not hold a change the way a page does (ADR 0033), so the host's values commit as they did before.
   */
  private Node tap(Cell of) {
    Runtime rt = host.rt;
    Node read;
    if (of instanceof StoreCell) {
      // A store's values are all there; only a derived one has a node to keep computed.
      read = ((StoreCell) of).deriveNode();
      if (read == null) {
        return null;
      }
    } else {
      read = ((ValueCell) of).node;
    }
    Node node = read;
    Node[] made = new Node[1];
    rt.runWithOwner(null, () -> {
      rt.effectNode(prev -> {
        rt.read(node);
        return null;
      }, (value, prev) -> null, (error, cleanup) -> { }, true, effect -> {
        if (made[0] == null) {
          made[0] = effect;
        }
      });
      return null;
    });
    // A memo nothing read yet computes now, so the first state is its value and not "not computed".
    try {
      rt.untrack(() -> rt.read(node));
    } catch (RuntimeException | Error e) {
      // Pending or failed: the flags say which.
    }
    return made[0];
  }

  /**
   * The state of a value as the host has committed it, read from the engine's flags. A store's value is its snapshot,
   * the same object for each part of it that did not change since the last one.
   */
  private State stateOf(Cell of) {
    Runtime rt = host.rt;
    if (of instanceof StoreCell) {
      StoreCell store = (StoreCell) of;
      Node derive = store.deriveNode();
      boolean pending = derive != null && rt.pending(derive);
      Throwable error = derive != null && !pending && rt.failed(derive) ? rt.errorOf(derive) : null;
      boolean hasValue = derive == null || rt.hasValue(derive) && !rt.uninitialized(derive);
      return new State(hasValue, store.snapshotHere(), pending, error, false, 0);
    }
    ValueCell cell = (ValueCell) of;
    Node node = cell.node;
    boolean pending = rt.pending(node);
    Throwable error = !pending && rt.failed(node) ? rt.errorOf(node) : null;
    boolean hasValue = rt.hasValue(node) && !rt.uninitialized(node);
    return new State(hasValue, hasValue ? rt.committed(node) : null, pending, error, cell.committedFailed(), 0);
  }
}
