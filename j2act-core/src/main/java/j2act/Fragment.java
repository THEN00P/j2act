package j2act;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A list of siblings without a wrapping element, as produced by each(). */
public final class Fragment implements DomContent {

  final List<DomContent> children;

  public Fragment(Collection<? extends DomContent> children) {
    this.children = new ArrayList<>(children);
  }

  private Fragment(List<DomContent> children, boolean owned) {
    this.children = children;
  }

  /** These children, the list itself and not a copy: for a list the engine made and no one else changes. */
  static Fragment owning(List<DomContent> children) {
    return new Fragment(children, true);
  }
}
