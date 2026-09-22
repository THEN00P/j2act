package j2act.jakarta;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import j2act.J2Act;

/**
 * Supplies endpoints wired to the running J2Act and checks Origin against Host
 * (ADR 0008). The per-page session token already stops a foreign page from driving
 * a session, since it cannot read the token; the Origin check is defense in depth.
 */
final class J2ActConfigurator extends ServerEndpointConfig.Configurator {

  static final String ORIGIN_OK = J2ActConfigurator.class.getName() + ".originOk";

  private final J2Act j2Act;

  J2ActConfigurator(J2Act j2Act) {
    this.j2Act = j2Act;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getEndpointInstance(Class<T> endpointClass) {
    return (T) new J2ActEndpoint(j2Act);
  }

  @Override public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
    config.getUserProperties().put(ORIGIN_OK, sameOrigin(request.getHeaders()));
  }

  /** No Origin header (non-browser clients) passes; a browser Origin must match Host. */
  static boolean sameOrigin(Map<String, List<String>> headers) {
    String origin = first(headers, "Origin");
    if (origin == null) {
      return true;
    }
    String host = first(headers, "Host");
    try {
      URI uri = URI.create(origin);
      String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
      return host != null && host.equalsIgnoreCase(authority);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String first(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
        return header.getValue().get(0);
      }
    }
    return null;
  }
}
