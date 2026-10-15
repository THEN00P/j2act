package j2act;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tab's event handlers by id: bound while their element renders, dropped when it stops,
 * and run with the event their wire message describes (ADR 0013).
 */
final class Handlers {

  /** A handler that reads the raw wire message: client callbacks and onClick(action, then) results. */
  @FunctionalInterface
  interface RawHandler {
    void handle(Map<String, String> message) throws Exception;
  }

  static final class Entry {
    final Scope scope;
    final String event;
    /** A Handler, or a RawHandler for client callbacks and results. */
    final Object handler;

    Entry(Scope scope, String event, Object handler) {
      this.scope = scope;
      this.event = event;
      this.handler = handler;
    }
  }

  private final Tab tab;
  private final Map<String, Entry> byId = new HashMap<>();

  Handlers(Tab tab) {
    this.tab = tab;
  }

  /**
   * Binds a handler for the element at {@code path}. The id stays the same while that
   * element keeps rendering there, so an event sent against the previous render still
   * lands; it is dropped when the element stops rendering (ADR 0013).
   */
  String register(Scope scope, String path, String event, Object handler) {
    String slot = path + "|" + event;
    String handlerId = scope.previousHandlerIds.remove(slot);
    if (handlerId == null) {
      handlerId = tab.engine.newSecret(12);
    }
    byId.put(handlerId, new Entry(scope, event, handler));
    scope.handlerIds.put(slot, handlerId);
    return handlerId;
  }

  /** Called at the start of a scope's render: its current ids become reusable candidates. */
  void begin(Scope scope) {
    scope.previousHandlerIds = scope.handlerIds;
    scope.handlerIds = new LinkedHashMap<>();
  }

  /** Called at the end of a scope's render: ids of elements that did not render again are dropped. */
  void end(Scope scope) {
    for (String handlerId : scope.previousHandlerIds.values()) {
      byId.remove(handlerId);
    }
    scope.previousHandlerIds.clear();
  }

  void remove(Scope scope) {
    for (String handlerId : scope.handlerIds.values()) {
      byId.remove(handlerId);
    }
    scope.handlerIds.clear();
    for (String handlerId : scope.actionHandlerIds.values()) {
      byId.remove(handlerId);
    }
    scope.actionHandlerIds.clear();
    end(scope);
  }

  /**
   * A callback passed to a client action called outside render (ADR 0022). It lives until
   * the component unmounts or the next call of the same action replaces it.
   */
  String registerAction(Scope scope, String key, Object handler) {
    String handlerId = tab.engine.newSecret(12);
    String previous = scope.actionHandlerIds.put(key, handlerId);
    if (previous != null) {
      byId.remove(previous);
    }
    byId.put(handlerId, new Entry(scope, "callback", handler));
    return handlerId;
  }

  Entry get(String handlerId) {
    return byId.get(handlerId);
  }

  /** Test and diagnostics hook: number of handlers currently invokable. */
  int count() {
    return byId.size();
  }

  void clear() {
    byId.clear();
  }

  /** Runs the handler with the event its wire message describes. */
  @SuppressWarnings("unchecked")
  static void invoke(Entry entry, Map<String, String> message) throws Exception {
    String value = message.get("value");
    if (entry.handler instanceof RawHandler) {
      ((RawHandler) entry.handler).handle(message);
      return;
    }
    switch (entry.event) {
      case "click":
        ((Handler<ClickEvent>) entry.handler).handle(new ClickEvent());
        break;
      case "submit":
        ((Handler<SubmitEvent>) entry.handler).handle(new SubmitEvent(value));
        break;
      case "keydown":
        ((Handler<KeyEvent>) entry.handler).handle(new KeyEvent(message.get("key"), value,
          "true".equals(message.get("ctrlKey")), "true".equals(message.get("shiftKey")),
          "true".equals(message.get("altKey")), "true".equals(message.get("metaKey"))));
        break;
      case "pointerdown":
      case "pointerup":
        ((Handler<PointerEvent>) entry.handler).handle(new PointerEvent(message.get("pointerType"),
          number(message.get("clientX")), number(message.get("clientY"))));
        break;
      default:
        ((Handler<ValueEvent>) entry.handler).handle(new ValueEvent(value,
          UploadFile.fromWire(message)));
    }
  }

  private static double number(String value) {
    try {
      return value == null ? 0 : Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
