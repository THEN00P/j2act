package j2act;

/** Server-side event handler, run on the session's lane. Exceptions are logged, never sent to the client. */
@FunctionalInterface
public interface Handler<E> {

  void handle(E event) throws Exception;
}
