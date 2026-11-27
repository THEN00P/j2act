package j2act;

import j2act.internal.signals.Node;
import j2act.internal.signals.Runtime;
import java.util.concurrent.CompletableFuture;

/**
 * The states another graph published for one of its values, as a mirror here takes them (ADR 0037). What reads the
 * feed gets the value, a future while the source is pending, or the source's error, and runs again when a revision
 * changes any of that. Lane-only, the mirror's.
 */
final class Feed {

  private final Runtime rt;
  private final ReactiveHost host;
  /** Asks the source to load again, for a reset at a boundary here. */
  private final Runnable retry;
  private final Node version;
  private long versions;
  /** What the source last published; null until the first state came. */
  private Published.State state;
  /** The revision of that state, so an older one arriving late changes nothing. */
  private long revision = -1;
  /** What a read gets while the source is pending, completed when its next state comes. */
  private CompletableFuture<Object> waiting;
  /** The version the last read saw. */
  private long readAt = -1;
  /** The job in which a read last gave the source's error. */
  private long failedIn = -1;
  private Node failedVersion;

  Feed(ReactiveHost host, Runnable retry) {
    this.host = host;
    this.rt = host.rt;
    this.retry = retry;
    this.version = rt.signalNode(0L, true);
  }

  /** Tracked: what the source has, as a compute answers it. */
  Object read() throws Exception {
    long seen = (Long) rt.read(version);
    Published.State s = state;
    if (s == null || s.pending) {
      readAt = seen;
      return waiting();
    }
    if (s.error != null) {
      if (seen == readAt && host.jobsDone != failedIn) {
        // Run again, in a later job, with nothing new from the source: an errored(...) here was reset, so the source
        // loads again. The engine also runs a failed node again when something reads it, as the page does at first.
        retry.run();
        return waiting();
      }
      readAt = seen;
      failedIn = host.jobsDone;
      throw s.error instanceof Exception ? (Exception) s.error : new RuntimeException(s.error);
    }
    readAt = seen;
    return s.value;
  }

  private CompletableFuture<Object> waiting() {
    if (waiting == null) {
      waiting = new CompletableFuture<>();
    }
    return waiting;
  }

  /** A state the source published; one older than what this has changes nothing. */
  void accept(Published.State next) {
    if (state != null && next.revision <= revision) {
      return;
    }
    revision = next.revision;
    state = next;
    settle();
  }

  /** Actions here with writes to the source not yet applied there. Lane-only. */
  private int outbound;
  private Node outboundSignal;

  private Node outboundSignal() {
    if (outboundSignal == null) {
      outboundSignal = rt.signalNode(0, true);
    }
    return outboundSignal;
  }

  /** An action here sends writes to the source (+1), or heard back or failed (-1). */
  void sending(int delta) {
    outbound += delta;
    // At the end of this job, outside the action's transaction, which would hold it until the action ends.
    host.later(() -> rt.write(outboundSignal(), outbound));
  }

  /**
   * Tracked, for isPending only: an action here wrote the source and has not ended (ADR 0037). Only what asks whether
   * it is pending reads this, so what reads the value runs again only when the value changes.
   */
  boolean readOutbound() {
    return (Integer) rt.read(outboundSignal()) > 0;
  }

  private void settle() {
    Published.State next = state;
    if (next == null) {
      return;
    }
    CompletableFuture<Object> pending = waiting;
    if (pending == null) {
      rt.write(version, ++versions);
    } else if (!next.pending) {
      waiting = null;
      if (next.error != null) {
        pending.completeExceptionally(next.error);
      } else {
        pending.complete(next.value);
      }
    }
    rt.write(failedVersion(), ++versions);
  }

  private Node failedVersion() {
    if (failedVersion == null) {
      failedVersion = rt.signalNode(0L, true);
    }
    return failedVersion;
  }

  /** Tracked: the source's last load failed. */
  boolean readFailed() {
    rt.read(failedVersion());
    Published.State s = state;
    return s != null && !s.pending && (s.failed || s.error != null);
  }

  /** Untracked: the source keeps an old value through a failed load. */
  boolean failed() {
    Published.State s = state;
    return s != null && s.failed;
  }

  /**
   * The source is out of reach (ADR 0037): what reads this keeps the value it had, as pending, until a state comes
   * again.
   */
  void stale() {
    Published.State s = state;
    if (s == null || s.pending) {
      return;
    }
    state = new Published.State(s.hasValue, s.value, true, s.error, s.failed, s.revision);
    if (waiting == null) {
      rt.write(version, ++versions);
    }
  }

  /** The source started anew and numbers its revisions from the start again: its next state is taken. */
  void restart() {
    revision = -1;
  }

  /** The revision of the last state taken. */
  long revision() {
    return revision;
  }

  /** The value the source last published, or null. */
  Object value() {
    Published.State s = state;
    return s == null ? null : s.value;
  }
}
