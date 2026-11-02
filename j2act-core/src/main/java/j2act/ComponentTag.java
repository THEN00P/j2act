package j2act;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable component (ADR 0031). A slot keeps one instance for as long as it renders: setup()
 * runs once, creates what the component needs through {@link Reactive} and returns its view. A
 * factory call in a parent's view is only a description, whose Props the kept instance takes.
 *
 * <pre>{@code
 * public final class Counter extends ComponentTag {
 *   private final Prop<String> label = prop("");
 *
 *   public static Counter counter() { return new Counter(); }
 *   public Counter withLabel(String l) { label.set(l); return this; }
 *
 *   @Override protected View setup() {
 *     var count = createSignal(0);
 *     return () -> button(label.get() + ": " + count.get())
 *       .onClick(e -> count.set(count.get() + 1));
 *   }
 * }
 * }</pre>
 */
public abstract class ComponentTag implements DomContent {

  /** The Props its field initializers created. */
  final List<Primitive> primitives = new ArrayList<>();
  Scope scope;
  Object key;

  /** Runs once per slot, after injection: creates what this component needs and returns its view. */
  protected abstract View setup();

  /** On a re-render: takes the Props of this render's description, which is then dropped. */
  final void adoptProps(ComponentTag description) {
    for (int i = 0; i < primitives.size() && i < description.primitives.size(); i++) {
      ((Prop<?>) primitives.get(i)).adopt((Prop<?>) description.primitives.get(i));
    }
  }

  /** Slot identity among repeated siblings; never rendered (ADR 0019). */
  public ComponentTag withKey(Object key) {
    this.key = key;
    return this;
  }

  /** A tracked input, written by a with* builder: the only field a parent's description hands over. */
  protected final <T> Prop<T> prop() {
    return addProp(new Prop<>(null));
  }

  protected final <T> Prop<T> prop(T fallback) {
    return addProp(new Prop<>(fallback));
  }

  private <T> Prop<T> addProp(Prop<T> prop) {
    primitives.add(prop);
    return prop;
  }
}
