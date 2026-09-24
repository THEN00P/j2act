package j2act;

/**
 * A window() call failed in the browser (ADR 0022): name() is the DOMException's name,
 * such as NotAllowedError or QuotaExceededError, or the JS error's, such as the TypeError
 * of a missing API.
 */
public class BrowserException extends ClientException {

  private static final long serialVersionUID = 1L;

  public BrowserException(String name, String message) {
    super(name, message);
  }
}
