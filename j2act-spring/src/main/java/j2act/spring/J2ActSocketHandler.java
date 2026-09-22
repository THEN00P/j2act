package j2act.spring;

import java.io.IOException;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import j2act.Connection;
import j2act.J2Act;

/** Bridges Spring WebSocket sessions to j2act connections. Sends are thread-safe via the decorator. */
public class J2ActSocketHandler extends TextWebSocketHandler {

  private static final String CONNECTION = J2ActSocketHandler.class.getName() + ".connection";
  private static final System.Logger LOG = System.getLogger("j2act.spring");

  private final J2Act j2Act;

  public J2ActSocketHandler(J2Act j2Act) {
    this.j2Act = j2Act;
  }

  @Override public void afterConnectionEstablished(WebSocketSession session) {
    session.getAttributes().put(CONNECTION, new SpringConnection(session));
  }

  @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    j2Act.onMessage(connection(session), message.getPayload());
  }

  @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    j2Act.onClose(connection(session));
  }

  private static Connection connection(WebSocketSession session) {
    return (Connection) session.getAttributes().get(CONNECTION);
  }

  static final class SpringConnection implements Connection {
    private final WebSocketSession session;

    SpringConnection(WebSocketSession raw) {
      this.session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 1 << 20);
    }

    @Override public void send(String message) {
      try {
        if (session.isOpen()) {
          session.sendMessage(new TextMessage(message));
        }
      } catch (IOException | IllegalStateException e) {
        LOG.log(System.Logger.Level.DEBUG, "socket send failed", e);
      }
    }

    @Override public void close() {
      try {
        session.close();
      } catch (IOException e) {
        LOG.log(System.Logger.Level.DEBUG, "socket close failed", e);
      }
    }
  }
}
