package j2act;

/** A file the runtime serves, with its content type (ADR 0022). */
public final class Asset {

  private final byte[] bytes;
  private final String contentType;

  Asset(byte[] bytes, String contentType) {
    this.bytes = bytes;
    this.contentType = contentType;
  }

  public byte[] bytes() {
    return bytes;
  }

  public String contentType() {
    return contentType;
  }

  static String contentType(String path) {
    String name = path.toLowerCase(java.util.Locale.ROOT);
    if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
      return "text/javascript;charset=UTF-8";
    }
    if (name.endsWith(".css")) {
      return "text/css;charset=UTF-8";
    }
    if (name.endsWith(".json") || name.endsWith(".map")) {
      return "application/json;charset=UTF-8";
    }
    if (name.endsWith(".svg")) {
      return "image/svg+xml";
    }
    if (name.endsWith(".png")) {
      return "image/png";
    }
    if (name.endsWith(".woff2")) {
      return "font/woff2";
    }
    if (name.endsWith(".woff")) {
      return "font/woff";
    }
    if (name.endsWith(".wasm")) {
      return "application/wasm";
    }
    return "application/octet-stream";
  }
}
