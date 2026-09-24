package j2act;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
  /** Attributes this render removed on purpose, e.g. withCondChecked(false): controlled, not absent. */
  final Set<String> removed = new HashSet<>();
  final Map<String, Handler<?>> events = new LinkedHashMap<>();
  Object key;
  long debounceMillis = -1;
  long throttleMillis = -1;
  Preload preload;
  String keyFilter;
  DomContent pending;
  /** A Mount, or a client handle without props (ADR 0022). */
  Object client;
  Supplier<? extends CompletionStage<?>> clickAction;
  Consumer<Object> clickThen;

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

  /** Sets an attribute; a null value removes it and marks it as removed on purpose. */
  @Override public T attr(String name, Object value) {
    if (value == null) {
      attributes.remove(name);
      removed.add(name);
    } else {
      attributes.put(name, String.valueOf(value));
      removed.remove(name);
    }
    return self();
  }

  /** Sets a boolean attribute such as disabled or checked. */
  @Override public T attr(String name) {
    attributes.put(name, "");
    removed.remove(name);
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
    clickAction = null;
    clickThen = null;
    return self();
  }

  /**
   * Runs a client action inside the click itself, so gesture-gated browser APIs such as
   * clipboard, share and fullscreen work, then hands its result to then on the lane
   * (ADR 0022). The action is one call on a client handle, e.g.
   * onClick(camera::snapshot, photo::set); its arguments are taken when this renders.
   */
  @SuppressWarnings("unchecked")
  public <V> T onClick(Supplier<? extends CompletionStage<V>> action,
                       Consumer<? super V> then) {
    events.remove("click");
    clickAction = Objects.requireNonNull(action);
    clickThen = (Consumer<Object>) Objects.requireNonNull(then);
    return self();
  }

  /**
   * Attaches a client module to this element with its props (ADR 0022). The element type
   * must match the Mount: a Mount&lt;VideoTag&gt; only fits video(). The element keeps its
   * identity across morphs, and its children belong to the client; server attributes still
   * update and attributes the client added stay.
   */
  public T withClient(Mount<T> mount) {
    this.client = Objects.requireNonNull(mount);
    return self();
  }

  /** Attaches a client module that has no mount(...) props. */
  public T withClient(Client client) {
    this.client = Objects.requireNonNull(client);
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

  /** A form submit; the browser's own submit is prevented and the fields arrive decoded. */
  public T onSubmit(Handler<SubmitEvent> handler) {
    events.put("submit", handler);
    return self();
  }

  /** Every keydown, unless narrowed with withKeyFilter so typing does not become traffic. */
  public T onKeyDown(Handler<KeyEvent> handler) {
    events.put("keydown", handler);
    return self();
  }

  /** Only these DOM key names reach onKeyDown, filtered on the client, e.g. "Enter", "Escape". */
  public T withKeyFilter(String... keys) {
    this.keyFilter = String.join(" ", keys);
    return self();
  }

  public T onFocus(Handler<ValueEvent> handler) {
    events.put("focus", handler);
    return self();
  }

  public T onBlur(Handler<ValueEvent> handler) {
    events.put("blur", handler);
    return self();
  }

  /** Client-side debounce for this element's events. Default is none. */
  public T withDebounce(Duration debounce) {
    this.debounceMillis = debounce.toMillis();
    return self();
  }

  /**
   * Client-side throttle for this element's events: the first goes out at once, then at
   * most one per interval. Input and change send the latest value at the end of the
   * interval; clicks inside it are dropped. Default is none.
   */
  public T withThrottle(Duration throttle) {
    this.throttleMillis = throttle.toMillis();
    return self();
  }

  /**
   * For a link: pre-mount its target on hover, focus or touch so the click lands on loaded
   * data (ADR 0011). Guards and queries run ahead; Effects wait for the click. Overrides the
   * mount's default; Preload.NONE opts a link out.
   */
  public T withPreload(Preload mode) {
    this.preload = mode;
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
