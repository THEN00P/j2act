package j2act;

/** One client socket, implemented by the transport adapter. send() may be called from lane threads. */
public interface Connection {

  void send(String message);

  void close();
}
