package j2act.cluster;

import java.time.Duration;

/**
 * Which server holds each @SharedScoped store (ADR 0037): one at a time, for a term it renews while it has the store.
 * Each new holder gets a higher epoch, which goes with what the holder sends and saves, so a holder that lost its
 * lease, as across a network split, is told apart from the one that has it and cannot keep writing.
 */
public interface SharedLeases {

  /** A store's holder and its epoch, until expiresAt, in milliseconds since the epoch. */
  final class Lease {
    public final String store;
    public final Node holder;
    public final long epoch;
    public final long expiresAt;

    public Lease(String store, Node holder, long epoch, long expiresAt) {
      this.store = store;
      this.holder = holder;
      this.epoch = epoch;
      this.expiresAt = expiresAt;
    }
  }

  /**
   * Who holds store now. When nobody does, or its holder's term ran out, candidate gets it, with a higher epoch than
   * any before. Blocking; called off the lanes.
   */
  Lease acquire(String store, Node candidate, Duration term);

  /** Extends the term of a lease its holder still has; false when it is gone, and with it the store. */
  boolean renew(Lease lease, Duration term);

  /** The holder gives the store up, as when it ends there. */
  void release(Lease lease);
}
