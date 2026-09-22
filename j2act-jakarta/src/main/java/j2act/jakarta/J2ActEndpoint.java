package j2act.jakarta;

import java.io.IOException;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

import j2act.Connection;
import j2act.J2Act;

/** One socket per page; bridges jakarta.websocket to j2act connections. */
public final class J2ActEndpoint extends Endpoint {

  private static final System.Logger LOG = System.getLogger("j2act.jakarta");
  private static final String CONNECTION = J2ActEndpoint.class.getName() + ".connection";
  private static final int MAX_MESSAGE = 256 * 1024;

  private final J2Act j2Act;

  J2ActEndpoint(J2Act j2Act) {
    this.j2Act = j2Act;
  }

  @Override public void onOpen(Session session, EndpointConfig config) {
    if (Boolean.FALSE.equals(config.getUserProperties().get(J2ActConfigurator.ORIGIN_OK))) {
      close(session, "cross-origin socket refused");
      return;
    }
    session.setMaxTextMessageBufferSize(MAX_MESSAGE);
    JakartaConnection connection = new JakartaConnection(session);
    session.getUserProperties().put(CONNECTION, connection);
    session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text -> j2Act.onMessage(connection, text));
  }

  @Override public void onClose(Session session, CloseReason reason) {
    Object connection = session.getUserProperties().get(CONNECTION);
    if (connection != null) {
      j2Act.onClose((Connection) connection);
    }
  }

  @Override public void onError(Session session, Throwable error) {
    LOG.log(System.Logger.Level.DEBUG, "socket error", error);
  }

  private static void close(Session session, String why) {
    try {
      session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, why));
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG, "socket close failed", e);
    }
  }

  /** Basic remotes are not safe for concurrent sends; the lock covers lane and sweeper threads. */
  static final class JakartaConnection implements Connection {
    private final Session session;

    JakartaConnection(Session session) {
      this.session = session;
    }

    @Override public void send(String message) {
      synchronized (session) {
        try {
          if (session.isOpen()) {
            session.getBasicRemote().sendText(message);
          }
        } catch (IOException | IllegalStateException e) {
          LOG.log(System.Logger.Level.DEBUG, "socket send failed", e);
        }
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
