package j2act;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One client action call, captured while an onClick(camera::snapshot, photo::set) binding
 * renders: the browser runs it inside the click, where gesture-gated APIs work (ADR 0022).
 */
final class ClientCall {

  private static final ThreadLocal<List<Object>> RECORDING = new ThreadLocal<>();

  final ClientHandle handle;
  final Method method;
  final Object[] args;

  ClientCall(ClientHandle handle, Method method, Object[] args) {
    this.handle = handle;
    this.method = method;
    this.args = args;
  }

  /** Recorded client action calls and window() calls, while a gesture binding renders. */
  static List<Object> recording() {
    return RECORDING.get();
  }

  /**
   * Runs the binding's supplier in recording mode; it must make exactly one call, on a
   * client handle or through window(). Returns the ClientCall or WebPath.WebCall.
   */
  static Object record(Supplier<?> action) {
    List<Object> calls = new ArrayList<>();
    List<Object> outer = RECORDING.get();
    RECORDING.set(calls);
    try {
      action.get();
    } finally {
      if (outer == null) {
        RECORDING.remove();
      } else {
        RECORDING.set(outer);
      }
    }
    if (calls.size() != 1) {
      throw new IllegalStateException("a gesture binding takes one call, like camera::snapshot or"
        + " () -> window().navigator().share(data); this one made " + calls.size() + " (ADR 0022)");
    }
    return calls.get(0);
  }

  /** The T of the action's CompletionStage&lt;T&gt;, for reading its result. */
  static Type valueType(Method method) {
    Type returned = method.getGenericReturnType();
    return returned instanceof ParameterizedType ? ((ParameterizedType) returned).getActualTypeArguments()[0] : Object.class;
  }
}
