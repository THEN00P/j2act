package j2act;

/**
 * A constant part of a view (ADR 0036): a child expression the enhancer found to read nothing at all, only constants
 * and tag factories, which it builds once instead of on every run of the view. What it renders is the same each time,
 * so the first render keeps its HTML, and later ones write that. Shared by every tab, it is never changed.
 */
final class Hoisted implements DomContent {

  final DomContent tree;
  /** What it rendered, outside raw text; null until it first did. */
  volatile String html;

  Hoisted(DomContent tree) {
    this.tree = tree;
  }

  /** The key of what it holds, so it keeps its place among keyed siblings. */
  Object key() {
    return tree instanceof Tag ? ((Tag<?>) tree).key : null;
  }
}
