package j2act;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A session's client modules (ADR 0022): what an element with withClient renders, action
 * calls waiting for the patch or for their reply, and callbacks into Java. Lane-only.
 * Failures are loud: a rejected action nobody handles, a thrown mount and a missing
 * export are logged with their component.
 */
final class Clients {

  static final long TIMEOUT_MILLIS = 30_000;

  private final Session session;
  private final Map<String, ClientCell> byId = new HashMap<>();
  private final Map<String, PendingCall> calls = new HashMap<>();
  private final List<String> outbox = new ArrayList<>();
  /** Live components given to actions, by anchor. */
  private final Map<String, Scope> live = new HashMap<>();
  private long callSeq;

  Clients(Session session) {
    this.session = session;
  }

  /** What an element carrying withClient renders. */
  static final class Binding {
    final String id;
    final String url;
    final String export;
    /** The mount props as JSON, or null for a client without mount(...). */
    String props;
    /** DomContent props, rendered as the element's first children. */
    final List<Slot> slots = new ArrayList<>();

    Binding(String id, String url, String export) {
      this.id = id;
      this.url = url;
      this.export = export;
    }
  }

  static final class Slot {
    final String id;
    final String name;
    final DomContent content;

    Slot(String id, String name, DomContent content) {
      this.id = id;
      this.name = name;
      this.content = content;
    }
  }

  // ---- rendering

  Binding bind(Scope scope, String path, Object client, long epoch) {
    Mount<?> mount = client instanceof Mount ? (Mount<?>) client : null;
    ClientHandle handle = mount != null ? mount.handle : ClientHandle.of(client);
    if (handle == null) {
      throw new IllegalArgumentException("withClient takes a handle from client(...) or its mount(...) (ADR 0022)");
    }
    ClientCell cell = handle.clientCell();
    if (cell.mountedEpoch == epoch) {
      throw new IllegalStateException("a " + handle.type.getSimpleName() + " handle mounts on one element, and this"
        + " render mounted it twice; lists put their client in a row component (ADR 0022)");
    }
    cell.mountedEpoch = epoch;
    byId.put(cell.id, cell);
    Binding binding = new Binding(cell.id, session.engine.modules.url(handle.type), Modules.exportName(handle.type));
    if (mount == null) {
      return binding;
    }
    String[] names = session.engine.modules.mountParams(mount.method);
    Class<?>[] classes = mount.method.getParameterTypes();
    Type[] types = mount.method.getGenericParameterTypes();
    StringBuilder props = new StringBuilder("{");
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      props.append(i == 0 ? "" : ",");
      Json.string(name, props);
      props.append(':');
      Object value = mount.props[i];
      if (value instanceof DomContent) {
        String slot = cell.id + "." + name;
        binding.slots.add(new Slot(slot, name, (DomContent) value));
        props.append("{\"$slot\":");
        Json.string(slot, props);
        props.append('}');
      } else {
        props.append(encode(value, classes[i], types[i],
          handler -> session.registerHandler(scope, path + "/client:" + name, "callback", handler), null,
          handle.type.getSimpleName() + ".mount " + name));
      }
    }
    binding.props = props.append('}').toString();
    return binding;
  }

  /**
   * An action bound to an event's gesture, e.g. onClick(camera::snapshot, photo::set): the
   * handler that takes the result, and the call the browser makes inside the event.
   */
  String[] bindAction(Scope scope, String path, String event, Tag.ActionBinding binding) {
    Object recorded = ClientCall.record(binding.action);
    Consumer<Object> then = binding.then;
    if (recorded instanceof WebPath.WebCall) {
      WebPath.WebCall web = (WebPath.WebCall) recorded;
      String handlerId = session.registerHandler(scope, path, event, (Session.RawHandler) message ->
        onBoundResult(message, "window call " + web.name, event, scope, then,
          json -> web.decoder.decode(Json.parse(json))));
      return new String[] {handlerId, "{\"w\":" + web.json + "}"};
    }
    ClientCall call = (ClientCall) recorded;
    ClientCell cell = call.handle.clientCell();
    String name = call.handle.type.getSimpleName() + "." + call.method.getName();
    Type valueType = ClientCall.valueType(call.method);
    String handlerId = session.registerHandler(scope, path, event, (Session.RawHandler) message ->
      onBoundResult(message, "client action " + name, event, scope, then,
        json -> session.engine.json.read(json, valueType)));
    StringBuilder json = new StringBuilder("{\"c\":");
    Json.string(cell.id, json);
    json.append(",\"n\":");
    Json.string(call.method.getName(), json);
    json.append(",\"a\":[");
    Class<?>[] classes = call.method.getParameterTypes();
    Type[] types = call.method.getGenericParameterTypes();
    for (int i = 0; i < call.args.length; i++) {
      int index = i;
      json.append(i == 0 ? "" : ",").append(encode(call.args[i], classes[i], types[i],
        handler -> session.registerHandler(scope, path + "/" + event + ":" + index, "callback", handler), null, name));
    }
    return new String[] {handlerId, json.append("]}").toString()};
  }

  private void onBoundResult(Map<String, String> message, String what, String event, Scope scope,
                             Consumer<Object> then, Function<String, Object> decode) {
    String error = message.get("x");
    if (error != null) {
      warn(what + " failed in its " + event + " in " + scope.instance.getClass().getName() + ": " + error);
      return;
    }
    String json = message.get("v");
    then.accept(decode.apply(json == null || json.isEmpty() ? "null" : json));
  }

  // ---- direct calls

  Object call(ClientCell cell, Method method, Object[] args) {
    String name = cell.type.getSimpleName() + "." + method.getName();
    String id = String.valueOf(++callSeq);
    Class<?>[] classes = method.getParameterTypes();
    Type[] types = method.getGenericParameterTypes();
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < args.length; i++) {
      String key = cell.id + "." + method.getName() + "#" + i;
      json.append(i == 0 ? "" : ",").append(encode(args[i], classes[i], types[i],
        handler -> session.registerActionHandler(cell.owner, key, handler),
        component -> live(cell, key, component), name));
    }
    outbox.add(Json.object("t", "ca", "i", id, "c", cell.id, "n", method.getName(), "a", json.append(']').toString()));
    CompletableFuture<Object> future = new CompletableFuture<>();
    Function<String, Object> decode = method.getReturnType() == void.class ? result -> null
      : result -> session.engine.json.read(result, ClientCall.valueType(method));
    calls.put(id, new PendingCall(cell, cell.owner, "client action " + name, decode, false, future));
    session.engine.later(session, TIMEOUT_MILLIS, () -> settle(id, null,
      new ClientException("TimeoutError", name + " did not settle within 30 seconds")));
    return method.getReturnType() == void.class ? null : future;
  }

  /**
   * A window() call from a handler or an effect (ADR 0022): sent after this update's patch,
   * in the same queue as client actions, so calls start in the order Java made them.
   */
  static CompletableFuture<Object> callWindow(ComponentTag owner, String name, String json,
                                              WebPath.Decoder<Object> decoder) {
    Scope scope = owner.scope;
    Session session = scope != null ? scope.session : Session.current();
    if (session == null) {
      throw new IllegalStateException(name + " needs a mounted component (ADR 0022)");
    }
    if (scope != null && scope.rendering) {
      throw new IllegalStateException(name + " runs in the browser; call it from a handler or effect, or bind it"
        + " with onClick, not in render() (ADR 0022)");
    }
    if (session.lane.isCurrent()) {
      return session.clients.window(scope, name, json, decoder);
    }
    CompletableFuture<Object> relay = new CompletableFuture<>();
    session.post(() -> session.clients.window(scope, name, json, decoder).whenComplete((value, error) -> {
      if (error != null) {
        relay.completeExceptionally(error);
      } else {
        relay.complete(value);
      }
    }));
    return relay;
  }

  private CompletableFuture<Object> window(Scope scope, String name, String json, WebPath.Decoder<Object> decoder) {
    String id = String.valueOf(++callSeq);
    outbox.add(Json.object("t", "wa", "i", id, "w", json));
    CompletableFuture<Object> future = new CompletableFuture<>();
    calls.put(id, new PendingCall(null, scope, "window call " + name, text -> decoder.decode(Json.parse(text)), true,
      future));
    session.engine.later(session, TIMEOUT_MILLIS, () -> settle(id, null,
      new BrowserException("TimeoutError", name + " did not settle within 30 seconds")));
    return future;
  }

  /**
   * A component passed to an action stays live, like React's createPortal: it renders on
   * its own State under the calling component, which owns it until it unmounts or the
   * next call of the same action replaces it (ADR 0022).
   */
  private String live(ClientCell cell, String key, ComponentTag component) {
    Scope owner = cell.owner;
    if (component.scope != null) {
      throw new IllegalArgumentException(component.getClass().getSimpleName()
        + " is already mounted; pass a new instance to " + cell.type.getSimpleName() + " (ADR 0022)");
    }
    Scope previous = owner.actionScopes.remove(key);
    if (previous != null) {
      previous.dispose();
    }
    live.values().removeIf(scope -> scope.disposed);
    Scope scope = new Scope(session, owner, owner.address + "/action:" + key);
    scope.detached = true;
    owner.actionScopes.put(key, scope);
    live.put(scope.anchor, scope);
    scope.bind(component);
    return session.renderDetached(scope);
  }

  /** The browser lost live content an action was given: stop patching that anchor, and say so. */
  void onLiveGone(String anchor) {
    Scope scope = anchor == null ? null : live.remove(anchor);
    if (scope == null || scope.disposed) {
      return;
    }
    scope.parent.actionScopes.values().remove(scope);
    String component = scope.instance == null ? "live content" : scope.instance.getClass().getName();
    scope.dispose();
    warn(component + " passed to a client action left the document, so it stopped updating");
  }

  /** Sends the calls made during this update, after its patches. */
  void sendOutbox() {
    for (String message : outbox) {
      session.send(message);
    }
    outbox.clear();
  }

  /** The browser's reply to a call: {t:"cr", i, ok, v or e}. */
  void onResult(Map<String, String> message) {
    String id = message.get("i");
    PendingCall call = id == null ? null : calls.get(id);
    if (call == null) {
      return;
    }
    if (!"1".equals(message.get("ok"))) {
      settle(id, null, error(message.get("e"), call.browser));
      return;
    }
    Object value;
    try {
      String json = message.get("v");
      value = call.decode.apply(json == null || json.isEmpty() ? "null" : json);
    } catch (RuntimeException e) {
      settle(id, null, error("TypeError: the result of " + call.name + " does not bind: " + e.getMessage(), call.browser));
      return;
    }
    settle(id, value, null);
  }

  /** A client failed outside a call: its mount, update or cleanup threw, or its export is missing. */
  void onError(Map<String, String> message) {
    ClientCell cell = byId.get(message.get("c"));
    String who = cell == null ? "client " + message.get("c")
      : "client module " + Modules.exportName(cell.type) + " (" + cell.type.getName() + ")";
    warn(who + " failed: " + message.get("e"));
  }

  private void settle(String id, Object value, ClientException error) {
    PendingCall call = calls.remove(id);
    if (call == null) {
      return;
    }
    if (error == null) {
      call.future.complete(value);
      return;
    }
    boolean handled = call.future.getNumberOfDependents() > 0;
    call.future.completeExceptionally(error);
    if (!handled && !"AbortError".equals(error.name())) {
      String component = call.owner == null || call.owner.instance == null ? ""
        : " in " + call.owner.instance.getClass().getName();
      warn(call.name + " failed" + component + ": " + error.getMessage());
    }
  }

  /** The handle's component unmounted: its calls end. */
  void forget(ClientCell cell) {
    byId.remove(cell.id);
    for (Map.Entry<String, PendingCall> entry : new ArrayList<>(calls.entrySet())) {
      if (entry.getValue().cell == cell) {
        settle(entry.getKey(), null, new ClientException("AbortError", "its component unmounted"));
      }
    }
  }

  void dispose() {
    for (String id : new ArrayList<>(calls.keySet())) {
      settle(id, null, new ClientException("AbortError", "the session ended"));
    }
    outbox.clear();
    byId.clear();
  }

  // ---- values

  /** Callbacks and upload targets become ids the browser calls back with; the rest is the host's JSON. */
  private String encode(Object value, Class<?> declared, Type generic, Function<Session.RawHandler, String> register,
                        Function<ComponentTag, String> live, String where) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Upload) {
      Upload upload = (Upload) value;
      String id = register.apply(message -> {
        UploadFile file = UploadFile.fromWire(message.get("fi"), message.get("fn"), message.get("fs"), message.get("ft"));
        if (file == null) {
          throw new IllegalArgumentException("an UploadTarget send without a file");
        }
        upload.mutate(file);
      });
      return "{\"$upload\":" + quote(id) + "}";
    }
    Type[] parameters = callbackParameters(declared, generic);
    if (parameters != null) {
      String id = register.apply(message -> invoke(value, parameters, message.get("v")));
      return "{\"$fn\":" + quote(id) + "}";
    }
    if (value instanceof DomContent) {
      if (value instanceof ComponentTag) {
        if (live == null) {
          throw new IllegalArgumentException(where + ": a live component argument needs a direct call; a gesture-bound"
            + " action takes plain tags (ADR 0022)");
        }
        return "{\"$live\":" + quote(live.apply((ComponentTag) value)) + "}";
      }
      // Plain tags are a snapshot: rendered once, the client's from then on.
      StringBuilder html = new StringBuilder();
      try {
        StaticRenderer.render((DomContent) value, html, false);
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return "{\"$html\":" + quote(html.toString()) + "}";
    }
    return session.engine.json.write(value);
  }

  /** Runnable, Consumer and BiConsumer parameters arrive in JS as functions (ADR 0022). */
  private static Type[] callbackParameters(Class<?> declared, Type generic) {
    int count = declared == Runnable.class ? 0 : declared == Consumer.class ? 1 : declared == BiConsumer.class ? 2 : -1;
    if (count < 0) {
      return null;
    }
    Type[] out = new Type[count];
    Type[] args = generic instanceof ParameterizedType ? ((ParameterizedType) generic).getActualTypeArguments() : null;
    for (int i = 0; i < count; i++) {
      out[i] = args == null ? Object.class : args[i];
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private void invoke(Object callback, Type[] parameters, String json) {
    List<?> args = json == null || json.isEmpty() ? Collections.emptyList() : (List<?>) Json.parse(json);
    Object[] values = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      String arg = i < args.size() ? JsonBinding.basic().write(args.get(i)) : "null";
      values[i] = session.engine.json.read(arg, parameters[i]);
    }
    if (callback instanceof Runnable) {
      ((Runnable) callback).run();
    } else if (callback instanceof BiConsumer) {
      ((BiConsumer<Object, Object>) callback).accept(values[0], values[1]);
    } else {
      ((Consumer<Object>) callback).accept(values[0]);
    }
  }

  /** "NotAllowedError: denied" as the exception it names; window() failures are BrowserExceptions. */
  private static ClientException error(String text, boolean browser) {
    String name = "Error";
    String message = text == null || text.isEmpty() ? "no details" : text;
    int colon = message.indexOf(": ");
    if (colon > 0 && message.substring(0, colon).matches("\\w+")) {
      name = message.substring(0, colon);
      message = message.substring(colon + 2);
    }
    return browser ? new BrowserException(name, message) : new ClientException(name, message);
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder();
    Json.string(s, b);
    return b.toString();
  }

  private void warn(String message) {
    session.engine.log(System.Logger.Level.WARNING, message + " (ADR 0022)", null);
  }

  private static final class PendingCall {
    /** The client handle called, or null for a window() call. */
    final ClientCell cell;
    final Scope owner;
    final String name;
    final Function<String, Object> decode;
    final boolean browser;
    final CompletableFuture<Object> future;

    PendingCall(ClientCell cell, Scope owner, String name, Function<String, Object> decode, boolean browser,
                CompletableFuture<Object> future) {
      this.cell = cell;
      this.owner = owner;
      this.name = name;
      this.decode = decode;
      this.browser = browser;
      this.future = future;
    }
  }
}
