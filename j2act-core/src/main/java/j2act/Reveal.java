package j2act;

import java.util.Arrays;

/**
 * Shows the loading(...) boundaries it wraps in an order, rather than each as it is ready (ADR 0033,
 * Solid's Reveal). "sequential", the default, shows each in turn, and a later one waits for the one before
 * even if its data came first; collapsed() shows only the first waiting fallback. "together" shows them all
 * once all are ready. "natural" shows each as it is ready.
 *
 * <pre>{@code
 * reveal(
 *   loading(() -> summary(report.get())).withFallback(skeleton()),
 *   loading(() -> details(rows.get())).withFallback(skeleton())
 * ).withOrder("sequential").collapsed()
 * }</pre>
 */
public final class Reveal extends Boundary<Reveal> {

  String order = "sequential";
  boolean collapsed;
  /** The description of the newest render, whose children are what to render. */
  Reveal latest = this;

  Reveal(DomContent[] children) {
    super(children);
  }

  /** One of "sequential", "together" or "natural". */
  public Reveal withOrder(String order) {
    if (!Arrays.asList("sequential", "together", "natural").contains(order)) {
      throw new IllegalArgumentException("a reveal's order is sequential, together or natural, not " + order);
    }
    this.order = order;
    return this;
  }

  /** In a sequential order, only the first waiting boundary shows its fallback. */
  public Reveal collapsed() {
    this.collapsed = true;
    return this;
  }

  @Override void adopt(Boundary<?> description) {
    Reveal next = (Reveal) description;
    order = next.order;
    collapsed = next.collapsed;
    latest = next;
  }
}
