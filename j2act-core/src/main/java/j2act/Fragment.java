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
}
