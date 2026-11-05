package j2act;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A component wrapping what may stop rendering (ADR 0031): loading(...) and errored(...). Its
 * children render inside it; a read that stops them makes it show its fallback instead, keeping
 * what is mounted below, and it renders them again when the value lands.
 */
public abstract class Boundary<B extends Boundary<B>> extends ComponentTag {

  private final List<DomContent> children;
  private final Supplier<? extends DomContent> deferred;

  Boundary(DomContent[] children) {
    this.children = Arrays.asList(children);
    this.deferred = null;
  }

  Boundary(Supplier<? extends DomContent> deferred) {
    this.children = Collections.emptyList();
    this.deferred = deferred;
  }

  /** Lane-only. The children, evaluating a deferred child with the boundary's reads tracked. */
  final List<DomContent> children() {
    return deferred == null ? children : Collections.singletonList(deferred.get());
  }

  /** Takes what the newest description passed down; the kept instance's slot stays. */
  abstract void adopt(Boundary<?> description);

  @Override protected final View setup() {
    return () -> {
      throw new IllegalStateException("a boundary renders through the renderer");
    };
  }
}
