package j2act;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An element with children. Null children render nothing but keep their index (ADR 0019). */
public class ContainerTag extends Tag<ContainerTag> {

  final List<DomContent> children = new ArrayList<>();

  public ContainerTag(String name, DomContent... children) {
    super(name);
    with(children);
  }

  public ContainerTag with(DomContent... children) {
    if (children != null) {
      this.children.addAll(Arrays.asList(children));
    }
    return this;
  }

  public ContainerTag with(Iterable<? extends DomContent> children) {
    for (DomContent child : children) {
      this.children.add(child);
    }
    return this;
  }

  public ContainerTag withText(String text) {
    children.add(new Text(text));
    return this;
  }

  @Override boolean isVoid() {
    return false;
  }
}
