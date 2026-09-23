package j2act;

import java.util.Objects;

/** A guard's answer: let the navigation through, send it elsewhere, or refuse it. */
public final class Verdict {

  private static final Verdict ALLOW = new Verdict(Kind.ALLOW, null);
  private static final Verdict FORBIDDEN = new Verdict(Kind.FORBIDDEN, null);

  enum Kind { ALLOW, REDIRECT, FORBIDDEN }

  final Kind kind;
  final String target;

  private Verdict(Kind kind, String target) {
    this.kind = kind;
    this.target = target;
  }

  public static Verdict allow() {
    return ALLOW;
  }

  /** App-relative path such as "/login"; the context path is added for you. */
  public static Verdict redirect(String path) {
    return new Verdict(Kind.REDIRECT, Objects.requireNonNull(path));
  }

  /** 403 on a full load; the forbidden page over the socket. */
  public static Verdict forbidden() {
    return FORBIDDEN;
  }
}
