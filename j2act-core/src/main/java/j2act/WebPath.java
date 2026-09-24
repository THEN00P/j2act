package j2act;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A path into the browser's window, built locally and run remotely: what the generated
 * j2act.web facade is made of (ADR 0022). Getters of interfaces only add a step. A
 * terminal call sends the path with its last step after the update's patch, or, when an
 * onClick(action) or another gesture binding records it, runs it inside the event.
 * Values cross as plain JSON, so window() needs no JSON binding.
 */
public final class WebPath {

  /** A generated dictionary: its members as plain JSON values. */
  public interface Value {
    Object toJson();
  }

  /** Turns a plain JSON result (String, Long, Double, Boolean, List, Map or null) into the facade's type. */
  @FunctionalInterface
  public interface Decoder<T> {
    T decode(Object json);
  }

  private final ComponentTag owner;
  private final List<Object> steps;

  private WebPath(ComponentTag owner, List<Object> steps) {
    this.owner = owner;
    this.steps = steps;
  }

  static WebPath root(ComponentTag owner) {
    return new WebPath(owner, Collections.emptyList());
  }

  // ---- steps: nothing runs yet

  public WebPath get(String name) {
    return step(List.of("g", name));
  }

  /** An operation whose result is navigated further, such as getElementById. */
  public WebPath call(String name, Object... args) {
    return step(java.util.Arrays.asList("c", name, plain(java.util.Arrays.asList(args))));
  }

  private WebPath step(Object step) {
    List<Object> next = new ArrayList<>(steps);
    next.add(step);
    return new WebPath(owner, next);
  }

  // ---- terminal calls

  public <T> CompletionStage<T> read(String name, String shape, Decoder<T> decoder) {
    return run("get", name, List.of(), -1, -1, shape, decoder);
  }

  public CompletionStage<Void> write(String name, Object value) {
    return run("set", name, Collections.singletonList(value), -1, -1, null, WebPath::none);
  }

  /** An operation; a returned promise is awaited. */
  public <T> CompletionStage<T> invoke(String name, String shape, Decoder<T> decoder, Object... args) {
    return run("call", name, java.util.Arrays.asList(args), -1, -1, shape, decoder);
  }

  /** A callback-style operation: the browser passes a resolve at success and a reject at failure (-1: none). */
  public <T> CompletionStage<T> invokeCallbacks(String name, int success, int failure, String shape, Decoder<T> decoder,
                                                Object... args) {
    return run("call", name, java.util.Arrays.asList(args), success, failure, shape, decoder);
  }

  /** new target(...args) in the browser; the object stays there. */
  public CompletionStage<Void> construct(Object... args) {
    return run("new", null, java.util.Arrays.asList(args), -1, -1, null, WebPath::none);
  }

  @SuppressWarnings("unchecked")
  private <T> CompletionStage<T> run(String kind, String name, List<Object> args, int success, int failure,
                                     String shape, Decoder<T> decoder) {
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("p", steps);
    call.put("k", kind);
    if (name != null) {
      call.put("n", name);
    }
    call.put("a", plain(args));
    if (success >= 0) {
      call.put("cb", failure >= 0 ? List.of(success, failure) : List.of(success));
    }
    StringBuilder json = new StringBuilder();
    Json.write(call, json);
    if (shape != null) {
      json.setLength(json.length() - 1);
      json.append(",\"r\":").append(shape).append('}');
    }
    String what = describe(name);
    Decoder<Object> decode = (Decoder<Object>) decoder;
    List<Object> recording = ClientCall.recording();
    if (recording != null) {
      recording.add(new WebCall(what, json.toString(), decode));
      return new CompletableFuture<>();
    }
    return (CompletionStage<T>) Clients.callWindow(owner, what, json.toString(), decode);
  }

  private String describe(String name) {
    StringBuilder out = new StringBuilder("window");
    for (Object step : steps) {
      out.append('.').append(((List<?>) step).get(1));
    }
    return name == null ? "new " + out : out.append('.').append(name).toString();
  }

  /** Dictionaries to their members, lists and maps walked, everything else as JSON writes it. */
  private static Object plain(Object value) {
    if (value instanceof Value) {
      return plain(((Value) value).toJson());
    }
    if (value instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        out.put(String.valueOf(e.getKey()), plain(e.getValue()));
      }
      return out;
    }
    if (value instanceof List) {
      List<Object> out = new ArrayList<>();
      for (Object item : (List<?>) value) {
        out.add(plain(item));
      }
      return out;
    }
    return value;
  }

  /** A recorded window() call, for a gesture binding. */
  static final class WebCall {
    final String name;
    final String json;
    final Decoder<Object> decoder;

    WebCall(String name, String json, Decoder<Object> decoder) {
      this.name = name;
      this.json = json;
      this.decoder = decoder;
    }
  }

  // ---- decoders the generated facade uses

  public static <T> T decode(Object json, Decoder<T> decoder) {
    return decoder.decode(json);
  }

  public static Void none(Object json) {
    return null;
  }

  public static Object any(Object json) {
    return json;
  }

  public static String string(Object json) {
    return json == null ? null : String.valueOf(json);
  }

  public static Boolean bool(Object json) {
    return (Boolean) json;
  }

  public static Integer integer(Object json) {
    return json == null ? null : ((Number) json).intValue();
  }

  public static Long longValue(Object json) {
    return json == null ? null : ((Number) json).longValue();
  }

  public static Double number(Object json) {
    return json == null ? null : ((Number) json).doubleValue();
  }

  public static <T> List<T> list(Object json, Decoder<T> item) {
    if (json == null) {
      return null;
    }
    List<T> out = new ArrayList<>();
    for (Object value : (List<?>) json) {
      out.add(item.decode(value));
    }
    return out;
  }
}
