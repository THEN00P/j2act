package j2act;

import java.util.function.Supplier;

/**
 * Shows a fallback while what it wraps reads a value that is not loaded yet (ADR 0031). Only the
 * first load shows it: on a refresh a read returns the old value. On a page load, its children
 * stream in when ready.
 *
 * <pre>{@code
 * loading(
 *   productDetails().withProduct(product)
 * ).withFallback(spinner())
 *
 * loading(() -> p(user.get().name())).withFallback(spinner())
 * }</pre>
 */
public final class Loading extends Boundary<Loading> {

  DomContent fallback;
  Supplier<?> on;

  Loading(DomContent[] children) {
    super(children);
  }

  Loading(Supplier<? extends DomContent> deferred) {
    super(deferred);
  }

  public Loading withFallback(DomContent fallback) {
    this.fallback = fallback;
    return this;
  }

  /**
   * Shows the fallback again when what this reads changes and something under it is loading, for content that
   * has changed subject, such as a page's filter (ADR 0033): without it a loaded boundary keeps its content
   * through a reload and holds the change. Solid's Loading on.
   */
  public Loading on(Supplier<?> dependencies) {
    this.on = dependencies;
    return this;
  }

  @Override void adopt(Boundary<?> description) {
    Loading next = (Loading) description;
    fallback = next.fallback;
    on = next.on;
    latest = next;
  }

  /** The description of the newest render, whose children are what to render. */
  Loading latest = this;
}
