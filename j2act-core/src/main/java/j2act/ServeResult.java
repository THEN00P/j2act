package j2act;

/** Status and HTML for a full-page response. */
public final class ServeResult {

  private final int status;
  private final String html;

  ServeResult(int status, String html) {
    this.status = status;
    this.html = html;
  }

  public int status() {
    return status;
  }

  public String html() {
    return html;
  }
}
