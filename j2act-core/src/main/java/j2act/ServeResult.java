package j2act;

/** Status, HTML and, for redirects, the Location for a full-page response. */
public final class ServeResult {

  private final int status;
  private final String html;
  private final String location;

  ServeResult(int status, String html) {
    this(status, html, null);
  }

  ServeResult(int status, String html, String location) {
    this.status = status;
    this.html = html;
    this.location = location;
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
