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

  private static final ThreadLocal<List<ClientCall>> RECORDING = new ThreadLocal<>();

  final ClientHandle handle;
  final Method method;
  final Object[] args;

  ClientCall(ClientHandle handle, Method method, Object[] args) {
    this.handle = handle;
    this.method = method;
    this.args = args;
  }

  static List<ClientCall> recording() {
    return RECORDING.get();
  }

  /** Runs the binding's supplier in recording mode; it must make exactly one client action call. */
  static ClientCall record(Supplier<?> action) {
    List<ClientCall> calls = new ArrayList<>();
    List<ClientCall> outer = RECORDING.get();
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
      throw new IllegalStateException("onClick(action, then) takes one client action call, like camera::snapshot;"
        + " this one made " + calls.size() + " (ADR 0022)");
    }
    return calls.get(0);
  }

  /** The T of the action's CompletionStage&lt;T&gt;, for reading its result. */
  static Type valueType(Method method) {
    Type returned = method.getGenericReturnType();
    return returned instanceof ParameterizedType ? ((ParameterizedType) returned).getActualTypeArguments()[0] : Object.class;
  }
}
