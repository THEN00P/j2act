package j2act;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An HTML element. Params are children only; everything else is a with* builder
 * (ADR 0005). Global attributes come from the generated {@link GlobalAttributes};
 * element-specific ones live on the generated tag classes in j2act-html. Event
 * handlers, debounce and pending are declared here and shipped to the client as
 * attributes when the element renders (ADR 0013).
 */
public abstract class Tag<T extends Tag<T>> implements DomContent, GlobalAttributes<T> {

  final String name;
  final Map<String, String> attributes = new LinkedHashMap<>();
  final Map<String, Handler<?>> events = new LinkedHashMap<>();
  Object key;
  long debounceMillis = -1;
  DomContent pending;

  protected Tag(String name) {
    this.name = Objects.requireNonNull(name);
  }

  abstract boolean isVoid();

  @Override
  @SuppressWarnings("unchecked")
  public final T self() {
    return (T) this;
  }

  public String getTagName() {
    return name;
  }

  /** Sets an attribute; a null value removes it. */
  @Override public T attr(String name, Object value) {
    if (value == null) {
      attributes.remove(name);
    } else {
      attributes.put(name, String.valueOf(value));
    }
    return self();
  }

  /** Sets a boolean attribute such as disabled or checked. */
  @Override public T attr(String name) {
    attributes.put(name, "");
    return self();
  }

  public T condAttr(boolean condition, String name, Object value) {
    return condition ? attr(name, value) : self();
  }

  /** Space-joins the given classes, skipping nulls so iff(...) can drop one. */
  public T withClasses(String... classes) {
    StringBuilder joined = new StringBuilder();
    for (String cls : classes) {
      if (cls != null && !cls.isEmpty()) {
        if (joined.length() > 0) {
          joined.append(' ');
        }
        joined.append(cls);
      }
    }
    return joined.length() == 0 ? self() : attr("class", joined.toString());
  }

  /** Sets data-{key}. */
  public T withData(String key, String value) {
    return attr("data-" + key, value);
  }

  public T withCondData(boolean condition, String key, String value) {
    return condition ? withData(key, value) : self();
  }

  /** Morph and slot identity for repeated rows; never rendered as HTML (ADR 0019). */
  public T withKey(Object key) {
    this.key = key;
    return self();
  }

  public T onClick(Handler<ClickEvent> handler) {
    events.put("click", handler);
    return self();
  }

  public T onInput(Handler<ValueEvent> handler) {
    events.put("input", handler);
    return self();
  }

  public T onChange(Handler<ValueEvent> handler) {
    events.put("change", handler);
    return self();
  }

  /** Client-side debounce for this element's events. Default is none. */
  public T withDebounce(Duration debounce) {
    this.debounceMillis = debounce.toMillis();
    return self();
  }

  /** Markup swapped in on the client the instant a click is sent, reverted on ack. */
  public T withPending(DomContent content) {
    this.pending = content;
    return self();
  }

  /** Renders to HTML outside any session: no handlers, components render with initial state. */
  public String render() {
    StringBuilder out = new StringBuilder();
    render(out);
    return out.toString();
  }

  public void render(Appendable out) {
    try {
      StaticRenderer.render(this, out, false);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Like render(), with one element per line and four-space indentation. */
  public String renderFormatted() {
    StringBuilder out = new StringBuilder();
    try {
      StaticRenderer.render(this, out, true);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toString();
  }

  @Override public String toString() {
    return render();
  }
}
