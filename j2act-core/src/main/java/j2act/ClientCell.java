package j2act;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A client handle's slot: its stable client id and which render pass mounted it. */
final class ClientCell extends Cell {

  final Class<?> type;
  final String id;
  long mountedEpoch = -1;

  ClientCell(Session session, String address, Class<?> type) {
    super(session, address);
    this.type = type;
    this.id = session.engine.newSecret(9);
  }

  @Override Object peek() {
    return null;
  }

  /** An action called directly, from a handler or an effect: it runs after this update's patch. */
  Object call(Method method, Object[] args) {
    if (owner != null && owner.rendering) {
      throw new IllegalStateException(type.getSimpleName() + "." + method.getName()
        + "() is a client action; call it from a handler or effect, or bind it with onClick, not in render() (ADR 0022)");
    }
    if (session.lane.isCurrent()) {
      return session.clients.call(this, method, args);
    }
    // From another thread, e.g. a query loader's continuation: hop onto the lane first.
    CompletableFuture<Object> relay = new CompletableFuture<>();
    session.post(() -> {
      Object stage = session.clients.call(this, method, args);
      if (stage instanceof CompletionStage) {
        ((CompletionStage<?>) stage).whenComplete((value, error) -> {
          if (error != null) {
            relay.completeExceptionally(error);
          } else {
            relay.complete(value);
          }
        });
      }
    });
    return method.getReturnType() == void.class ? null : relay;
  }

  @Override void dispose() {
    session.clients.forget(this);
    super.dispose();
  }
}
