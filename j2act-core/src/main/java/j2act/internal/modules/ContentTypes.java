package j2act.internal.modules;

/** Content types of the files the runtime serves, by extension. */
final class ContentTypes {

  private ContentTypes() {
  }

  static String of(String path) {
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
