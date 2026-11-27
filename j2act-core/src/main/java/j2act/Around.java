package j2act;

/**
 * A parent's HTML, searched for what its children brought, in their order. A scope's children appear in its HTML in
 * the order they were placed, so each search starts where the last one ended, and a walk over a list of rows reads the
 * list's HTML once, not once per row; one out of that order is still found, by a search from the start.
 */
final class Around {

  private final String text;
  private int from;

  Around(String text) {
    this.text = text;
  }

  /** Whether the HTML holds what, exactly as String.contains says. */
  boolean contains(String what) {
    int at = text.indexOf(what, from);
    if (at < 0 && from > 0) {
      at = text.indexOf(what);
      return at >= 0;
    }
    if (at < 0) {
      return false;
    }
    from = at;
    return true;
  }

  /** What a child brought: its HTML as it renders now, or for an attribute region its mark. */
  boolean brought(Scope child) {
    if (child.instance instanceof AttrTag) {
      return child.html != null && child.html.equals(child.embedded) && contains(child.mark());
    }
    return child.html != null && contains(child.html);
  }
}
