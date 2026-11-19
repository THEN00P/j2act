package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import java.util.List;
import java.util.Set;

/** Port of NodeExtension in @solidjs/signals core/types.ts at 9449845: machinery most nodes never touch. */
final class NodeExtension {
  /** The current flight; `inFlightSet` is false for Solid's onSupersede-only `undefined`. */
  Object inFlight;
  Runnable flightTeardown;
  Throwable error;
  boolean blocked;
  Set<Node> pendingSources;
  long served = -1;
  Runnable unobserved;
  /** L2: the previous pass's frame, parked for this node's commit. */
  Owner pendingFirstChild;
  List<Runnable> pendingDisposal;
  /** L2: the transaction holding this node while CONFIG_HELD is set. */
  Scheduler.Transaction transaction;
  /** The current flight re-asks the same question: verdict-quiet (A19 exc. 2, A24). */
  boolean reask;
  /** A28: the staging the last flush left on a held node. */
  Object flushed = Constants.NOT_PENDING;
  long flushedAt = -1;
  /** Provenance (A18): the question this node's guess or latest flight answers. */
  int q;
  /** #4544: the hold (a Transaction) or, before one, the flush clock (a Long) under which a window read this staging. */
  Object askedIn;
  /** Lanes: the node's lane has shown a value of it: a guess is value, and the lane's record holds what it covered. */
  boolean shown;
  /** affects() marks live on this node. */
  int marks;
  /** D-41: the error a node showed when a re-ask made it pending. */
  Throwable shownError;
  /** R5: the frame pass's flight of a latest()/isPending() reader. */
  Object parked;
}
