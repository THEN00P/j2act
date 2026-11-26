package j2act;

import j2act.internal.store.JavaShape;
import j2act.internal.store.Paths;

/**
 * A page's read of another graph's value or store part while it rendered (ADR 0037), and what it saw: what a handler
 * of that render was rendered from. A click on the handler runs only if every such part is still what it saw, by the
 * state its store last published; otherwise the page has not caught up with a change, and the click is refused.
 */
final class SharedRead {

  /** The other graph's value or store. */
  final Cell source;
  /** The graph that read it. */
  final ReactiveHost reader;
  /** For a store, the keys from its root to the part, null last for the whole object there; null for a value. */
  private final Object[] keys;
  private final int kind;
  /** For a value, the value seen; for a store, the snapshot the page's copy had, in which keys find the part seen. */
  private final Object seen;
  /** The part is inside a list's or a map's element. */
  final boolean inElement;

  /** For a check by the server that holds the store (ADR 0037): the keys to the part, or null for a value. */
  Object[] keys() {
    return keys;
  }

  int kind() {
    return kind;
  }

  SharedRead(ReactiveHost reader, Cell source, Object[] keys, int kind, Object seen, boolean inElement) {
    this.reader = reader;
    this.source = source;
    this.keys = keys;
    this.kind = kind;
    this.seen = seen;
    this.inElement = inElement;
  }

  /**
   * Whether a handler of a region inside the one that read this still depends on it. A region further out reads a
   * list's elements to place them, which says nothing of a button in one element; what decided that the button shows
   * is read inside it.
   */
  boolean countsFromOutside() {
    return kind != JavaShape.Reads.ELEMENT && !inElement;
  }

  /**
   * Whether the part is still what was seen, by what its store last published, or it changed only by the reader's own
   * writes since: a page that has not heard back of its own last click is not behind anyone else.
   */
  boolean holds() {
    Published.State now = source.tab.host.lastState(source);
    if (now == null) {
      return false;
    }
    Object[] steps = keys == null ? null : Paths.steps(seen, keys);
    for (Published.State state = now; state != null; state = state.before) {
      if (same(state.value, steps)) {
        return true;
      }
      if (reader == null || state.author != reader) {
        return false;
      }
    }
    return false;
  }

  private boolean same(Object value, Object[] steps) {
    if (steps == null) {
      return Paths.same(value, seen);
    }
    Object was = steps[steps.length - 1];
    Object is = Paths.at(value, steps, steps.length - 1);
    if (kind == JavaShape.Reads.ELEMENT) {
      // An element is there or not: what changed inside it, a read inside its region says.
      return (was == Paths.MISSING) == (is == Paths.MISSING);
    }
    return Paths.same(is, was);
  }

  /**
   * Lane-only, the scope's. What a handler of scope was rendered from: the parts its region and the regions around it
   * read, those around it only where they decide what shows inside, as a copy another lane may check.
   */
  static java.util.List<SharedRead> of(Scope scope) {
    java.util.List<SharedRead> reads = new java.util.ArrayList<>();
    for (Scope s = scope; s != null; s = s.parent) {
      for (SharedRead read : s.sharedReads()) {
        if (s == scope || read.countsFromOutside()) {
          reads.add(read);
        }
      }
    }
    return reads;
  }

  /**
   * Null when every part is still what was seen; otherwise the revision each part that changed is at now, which the
   * page has to have before it can tell whether its button still shows. Any thread.
   */
  static java.util.Map<Cell, Long> changed(java.util.List<SharedRead> reads) {
    java.util.Map<Cell, Long> changed = null;
    for (SharedRead read : reads) {
      if (!read.holds()) {
        if (changed == null) {
          changed = new java.util.IdentityHashMap<>();
        }
        Published.State now = read.source.tab.host.lastState(read.source);
        changed.merge(read.source, now == null ? Long.MAX_VALUE : now.revision, Math::max);
      }
    }
    return changed;
  }
}
