package j2act;

/**
 * A session's socket abuse limits (ADR 0013): token buckets for events and upload chunks that
 * follow identity changes, and the drop count that closes a socket which keeps overflowing.
 */
final class SocketLimits {

  private static final long OVERFLOW_WINDOW_MILLIS = 10_000;

  private final Session session;
  private final Object lock = new Object();
  private AuthCtx limitedAs;
  private RateLimit.Bucket eventBucket;
  private RateLimit.Bucket chunkBucket;
  private long overflowWindowStart;
  private int overflowCount;

  SocketLimits(Session session) {
    this.session = session;
  }

  /** Any thread. Takes a token for an event or upload chunk; the limit follows identity changes. */
  boolean admit(boolean chunk) {
    AuthCtx auth = session.currentAuth();
    long now = session.engine.clock.millis();
    synchronized (lock) {
      if (eventBucket == null || !auth.equals(limitedAs)) {
        RateLimit limit = session.engine.rateLimit.apply(auth);
        limitedAs = auth;
        eventBucket = new RateLimit.Bucket(limit, now);
        chunkBucket = new RateLimit.Bucket(limit, now);
      }
      return (chunk ? chunkBucket : eventBucket).tryTake(now);
    }
  }

  /** Any thread. Records a dropped message; true once drops are sustained enough to close the socket. */
  boolean overflowed() {
    long now = session.engine.clock.millis();
    synchronized (lock) {
      if (now - overflowWindowStart > OVERFLOW_WINDOW_MILLIS) {
        overflowWindowStart = now;
        overflowCount = 0;
        session.engine.log(System.Logger.Level.WARNING, "session " + session.id + " is over its rate limit; dropping events", null);
      }
      overflowCount++;
      return overflowCount > Math.max(50, eventBucket == null ? 0 : Math.min(eventBucket.limit.burst, 1000));
    }
  }
}
