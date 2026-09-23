package j2act;

import java.nio.file.Path;

/** A finished upload: where it was stored and what the browser called it. */
public final class UploadRef {

  private final Path path;
  private final String originalName;
  private final long size;
  private final String contentType;

  UploadRef(Path path, String originalName, long size, String contentType) {
    this.path = path;
    this.originalName = originalName;
    this.size = size;
    this.contentType = contentType;
  }

  /**
   * The stored file. Under withTarget it is yours; without one it sits in the framework's
   * upload directory and is deleted when the session ends, so move it in onSuccess.
   */
  public Path path() {
    return path;
  }

  /** The browser's file name, sanitized. */
  public String originalName() {
    return originalName;
  }

  public long size() {
    return size;
  }

  public String contentType() {
    return contentType;
  }

  @Override public String toString() {
    return "UploadRef(" + path + ")";
  }
}
