package j2act;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shows a fallback when what it wraps reads a value whose last load failed (ADR 0031). The
 * fallback gets the failure and a reset, which loads the failed values again and renders the
 * children once more.
 *
 * <pre>{@code
 * errored(
 *   profileCard().withUser(user)
 * ).withFallback((err, reset) -> div(
 *   p("Couldn't load: " + err.getMessage()),
 *   button("Try again").onClick(e -> reset.run())
 * ))
 * }</pre>
 */
public final class Errored extends Boundary<Errored> {

  BiFunction<Throwable, Runnable, ? extends DomContent> fallback;

  Errored(DomContent[] children) {
    super(children);
  }

  Errored(Supplier<? extends DomContent> deferred) {
    super(deferred);
  }

  public Errored withFallback(Function<Throwable, ? extends DomContent> fallback) {
    this.fallback = (error, reset) -> fallback.apply(error);
    return this;
  }

  public Errored withFallback(BiFunction<Throwable, Runnable, ? extends DomContent> fallback) {
    this.fallback = fallback;
    return this;
  }

  @Override void adopt(Boundary<?> description) {
    Errored next = (Errored) description;
    fallback = next.fallback;
    latest = next;
  }

  /** The description of the newest render, whose children are what to render. */
  Errored latest = this;
}
