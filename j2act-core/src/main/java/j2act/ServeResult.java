package j2act;

/** Status, HTML, cookies and, for redirects, the Location for a full-page response. */
public final class ServeResult {

  private final int status;
  private final String html;
  private final String location;
  private final java.util.List<String> setCookies;
  private PageStream stream;

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

  /** Where a streaming response goes (ADR 0031); called from a tab's lane, one call at a time. */
  public interface ChunkSink {
    /** Writes and flushes one piece of the page. */
    void write(String chunk) throws java.io.IOException;

    /** The page is complete. */
    void end();
  }

  ServeResult withStream(PageStream next) {
    stream = next;
    return this;
  }

  /**
   * After html() goes out, the rest of the page follows: send it to streamTo instead of ending the
   * response, without buffering, and with X-Accel-Buffering: no so a proxy does not hold it (ADR 0031).
   */
  public boolean streams() {
    return stream != null;
  }

  /** Receives the rest of the page, as it is ready. */
  public void streamTo(ChunkSink sink) {
    stream.attach(sink);
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
