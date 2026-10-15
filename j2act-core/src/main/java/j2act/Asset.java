package j2act;

/** A file the runtime serves, with its content type (ADR 0022). */
public final class Asset {

  private final byte[] bytes;
  private final String contentType;

  public Asset(byte[] bytes, String contentType) {
    this.bytes = bytes;
    this.contentType = contentType;
  }

  public byte[] bytes() {
    return bytes;
  }

  public String contentType() {
    return contentType;
  }
}
