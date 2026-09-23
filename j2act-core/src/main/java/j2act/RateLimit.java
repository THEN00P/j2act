package j2act;

/**
 * A token bucket shape for socket abuse limits (ADR 0013): a steady rate plus a burst.
 * Chosen per session from its AuthCtx, e.g.
 * withRateLimit(auth -> auth.isAnonymous() ? RateLimit.perSecond(10) : RateLimit.perSecond(50)).
 */
public final class RateLimit {

  private static final RateLimit UNLIMITED = new RateLimit(Double.POSITIVE_INFINITY, Integer.MAX_VALUE);

  final double perSecond;
  final int burst;

  private RateLimit(double perSecond, int burst) {
    if (!(perSecond > 0) || burst < 1) {
      throw new IllegalArgumentException("rate and burst must be positive");
    }
    this.perSecond = perSecond;
    this.burst = burst;
  }

  /** n per second, with a burst of n. */
  public static RateLimit perSecond(int n) {
    return new RateLimit(n, n);
  }

  /** n per minute, with a burst of n. */
  public static RateLimit perMinute(int n) {
    return new RateLimit(n / 60.0, n);
  }

  public static RateLimit unlimited() {
    return UNLIMITED;
  }

  /** How many may arrive at once after a quiet spell. */
  public RateLimit withBurst(int burst) {
    return new RateLimit(perSecond, burst);
  }

  @Override public String toString() {
    return this == UNLIMITED ? "RateLimit(unlimited)" : "RateLimit(" + perSecond + "/s, burst " + burst + ")";
  }

  /** One session's bucket for one kind of traffic. Thread-safe. */
  static final class Bucket {
    final RateLimit limit;
    private double tokens;
    private long refilledAt;

    Bucket(RateLimit limit, long now) {
      this.limit = limit;
      this.tokens = limit.burst;
      this.refilledAt = now;
    }

    synchronized boolean tryTake(long now) {
      if (limit == UNLIMITED) {
        return true;
      }
      tokens = Math.min(limit.burst, tokens + (now - refilledAt) / 1000.0 * limit.perSecond);
      refilledAt = now;
      if (tokens >= 1) {
        tokens -= 1;
        return true;
      }
      return false;
    }
  }
}
