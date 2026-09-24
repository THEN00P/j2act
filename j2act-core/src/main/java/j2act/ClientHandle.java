package j2act;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What client(Camera.class) creates: a slot-bound primitive whose proxy implements the
 * client interface (ADR 0022). mount(...) returns a Mount for withClient; any other
 * method is an action, sent after the update's patch, or recorded while an
 * onClick(action, then) binding renders.
 */
final class ClientHandle extends Primitive implements InvocationHandler {

  final Class<?> type;
  final Object proxy;

  ClientHandle(Class<?> type) {
    if (!type.isInterface() || !Client.class.isAssignableFrom(type)) {
      throw new IllegalArgumentException(type.getName() + " must be an interface extending Client (ADR 0022)");
    }
    this.type = type;
    this.proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this);
  }

  /** The handle behind a proxy from client(...), or null. */
  static ClientHandle of(Object client) {
    if (client != null && Proxy.isProxyClass(client.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(client);
      if (handler instanceof ClientHandle) {
        return (ClientHandle) handler;
      }
    }
    return null;
  }

  @Override Cell createCell(Session session, String address) {
    return new ClientCell(session, address, type);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof ClientCell && ((ClientCell) cell).type == type;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }

  ClientCell clientCell() {
    return (ClientCell) requireCell();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
    Object[] values = args == null ? new Object[0] : args;
    if (method.getDeclaringClass() == Object.class) {
      switch (method.getName()) {
        case "equals":
          return self == values[0];
        case "hashCode":
          return System.identityHashCode(self);
        default:
          return type.getSimpleName() + " client";
      }
    }
    if (method.isDefault()) {
      // A Java-side helper on the interface, not an action.
      return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
        .unreflectSpecial(method, type)
        .bindTo(self)
        .invokeWithArguments(values);
    }
    if (method.getReturnType() == Mount.class) {
      return new Mount(this, method, values);
    }
    List<Object> recording = ClientCall.recording();
    if (recording != null) {
      recording.add(new ClientCall(this, method, values));
      return method.getReturnType() == void.class ? null : new CompletableFuture<>();
    }
    return clientCell().call(method, values);
  }
}
