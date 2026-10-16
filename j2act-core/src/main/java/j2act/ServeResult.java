package j2act;

/** Status, HTML, cookies and, for redirects, the Location for a full-page response. */
public final class ServeResult {

  private final int status;
  private final String html;
  private final String location;
  private final java.util.List<String> setCookies;

  ServeResult(int status, String html) {
    this(status, html, null);
  }

  ServeResult(int status, String html, String location) {
    this(status, html, location, java.util.Collections.emptyList());
  }

  ServeResult(int status, String html, String location, java.util.List<String> setCookies) {
    this.status = status;
    this.html = html;
    this.location = location;
    this.setCookies = setCookies;
  }

  /**
   * Set-Cookie values the response must carry, such as the j2-browser cookie on first use of
   * browser state (ADR 0030). The adapter adds "; Secure" on HTTPS.
   */
  public java.util.List<String> setCookies() {
    return setCookies;
  }

  public int status() {
    return status;
  }

  public String html() {
    return html;
  }

  /** Where to redirect, with the context path, or null when this is not a redirect. */
  public String location() {
    return location;
  }
}
