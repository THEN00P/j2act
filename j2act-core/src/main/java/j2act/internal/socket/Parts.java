package j2act.internal.socket;

/**
 * A socket message that comes in parts, put together for j2act (ADR 0013). The container keeps its own small buffer,
 * as Tomcat's 8 KiB: one as large as j2act's frame limit would be held by every socket, while a page's messages are
 * small. Public for the adapters, not API.
 */
public final class Parts {
  private StringBuilder so;
  private boolean refused;

  /**
   * The message once its last part came; or, the first time it is longer than limit, what came of it up to one character
   * past the limit, which j2act refuses as too long, and nothing for the rest of its parts. Null while parts are still
   * to come. A message that came whole is given back as it is.
   */
  public String add(String part, boolean last, int limit) {
    if (refused) {
      refused = !last;
      return null;
    }
    if (so == null && last) {
      return part;
    }
    if (so == null) {
      so = new StringBuilder();
    }
    if (so.length() + part.length() > limit) {
      String text = so.append(part, 0, limit + 1 - so.length()).toString();
      so = null;
      refused = !last;
      return text;
    }
    so.append(part);
    if (!last) {
      return null;
    }
    String text = so.toString();
    so = null;
    return text;
  }
}
