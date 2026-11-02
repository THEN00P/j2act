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

  @Override void adopt(Boundary<?> description) {
    Loading next = (Loading) description;
    fallback = next.fallback;
    latest = next;
  }

  /** The description of the newest render, whose children are what to render. */
  Loading latest = this;
}
