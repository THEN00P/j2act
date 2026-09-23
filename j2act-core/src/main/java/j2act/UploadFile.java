package j2act;

/**
 * A file the user picked, as the browser described it in a change event. Name, size and
 * type are client claims: upload() checks them up front and enforces size on the bytes.
 */
public final class UploadFile {

  final String id;
  private final String name;
  private final long size;
  private final String contentType;

  UploadFile(String id, String name, long size, String contentType) {
    this.id = id;
    this.name = name;
    this.size = size;
    this.contentType = contentType;
  }

  /** From the wire fields a file input's change event carries (fi, fn, fs, ft), or null. */
  static UploadFile fromWire(String id, String name, String size, String type) {
    if (id == null || name == null || size == null) {
      return null;
    }
    try {
      long bytes = Long.parseLong(size);
      return bytes < 0 ? null : new UploadFile(id, name, bytes, type == null ? "" : type);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The browser's file name, unsanitized. */
  public String name() {
    return name;
  }

  public long size() {
    return size;
  }

  /** The browser's guess from the extension; may be empty. */
  public String contentType() {
    return contentType;
  }

  @Override public String toString() {
    return name + " (" + size + " bytes)";
  }
}
