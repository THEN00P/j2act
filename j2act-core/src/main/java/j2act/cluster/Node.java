package j2act.cluster;

import java.util.Objects;

/**
 * One server of a cluster (ADR 0037): the URL others reach its j2act at, with its context path, such as
 * http://10.0.0.5:8080/app, and the id it gives itself when it starts, or null where only the URL is known, as in a
 * static list. Two nodes are the same server when their URLs are.
 */
public final class Node {
  private final String id;
  private final String url;

  public Node(String id, String url) {
    this.id = id;
    this.url = normalized(url);
  }

  /** A server known only by its URL. */
  public static Node at(String url) {
    return new Node(null, url);
  }

  /** The id it chose at its start, or null when it is not known yet. */
  public String id() {
    return id;
  }

  public String url() {
    return url;
  }

  /** Where its peers connect: its socket, ws:// or wss:// as its URL is http:// or https://. */
  public java.net.URI socket() {
    String u = url.startsWith("https://") ? "wss://" + url.substring(8)
      : url.startsWith("http://") ? "ws://" + url.substring(7) : url;
    return java.net.URI.create(u + "/_j2act/ws");
  }

  private static String normalized(String url) {
    Objects.requireNonNull(url, "a node's URL");
    String u = url.trim();
    while (u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    return u;
  }

  @Override public boolean equals(Object other) {
    return other instanceof Node && ((Node) other).url.equals(url);
  }

  @Override public int hashCode() {
    return url.hashCode();
  }

  @Override public String toString() {
    return url;
  }
}
