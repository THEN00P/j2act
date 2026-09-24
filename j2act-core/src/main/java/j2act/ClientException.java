package j2act;

/**
 * A client module failed (ADR 0022): its action threw or rejected, it did not settle within
 * 30 seconds, its element was not mounted, or its export is missing. name() is the JS
 * error's name, such as NotAllowedError, TypeError or TimeoutError.
 */
public class ClientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String name;

  public ClientException(String name, String message) {
    super(name + ": " + message);
    this.name = name;
  }

  public String name() {
    return name;
  }
}
