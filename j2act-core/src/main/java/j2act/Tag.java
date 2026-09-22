package j2act;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An HTML element. Params are children only; everything else is a with* builder
 * (ADR 0005). Event handlers, debounce and pending are declared here and shipped to
 * the client as attributes when the element renders (ADR 0013).
 */
public abstract class Tag<T extends Tag<T>> implements DomContent {

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

  @SuppressWarnings("unchecked")
  protected final T self() {
    return (T) this;
  }

  /** Sets an attribute; a null value removes it. */
  public T attr(String name, Object value) {
    if (value == null) {
      attributes.remove(name);
    } else {
      attributes.put(name, String.valueOf(value));
    }
    return self();
  }

  /** Sets a boolean attribute such as disabled or checked. */
  public T attr(String name) {
    attributes.put(name, "");
    return self();
  }

  public T withId(String id) {
    return attr("id", id);
  }

  public T withClass(String cls) {
    return attr("class", cls);
  }

  public T withHref(String href) {
    return attr("href", href);
  }

  public T withSrc(String src) {
    return attr("src", src);
  }

  public T withRel(String rel) {
    return attr("rel", rel);
  }

  public T withType(String type) {
    return attr("type", type);
  }

  public T withName(String name) {
    return attr("name", name);
  }

  public T withContent(String content) {
    return attr("content", content);
  }

  public T withValue(Object value) {
    return attr("value", value);
  }

  public T withPlaceholder(String placeholder) {
    return attr("placeholder", placeholder);
  }

  public T withStyle(String style) {
    return attr("style", style);
  }

  public T withTitle(String title) {
    return attr("title", title);
  }

  public T withFor(String id) {
    return attr("for", id);
  }

  public T withLang(String lang) {
    return attr("lang", lang);
  }

  public T withCharset(String charset) {
    return attr("charset", charset);
  }

  public T withDisabled(boolean disabled) {
    return disabled ? attr("disabled") : attr("disabled", null);
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
}
