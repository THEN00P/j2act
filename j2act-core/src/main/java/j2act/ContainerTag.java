package j2act;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** An element with children. Null children render nothing but keep their index (ADR 0019). */
public abstract class ContainerTag<T extends ContainerTag<T>> extends Tag<T> {

  final List<DomContent> children = new ArrayList<>();

  protected ContainerTag(String name) {
    super(name);
  }

  public T with(DomContent... children) {
    if (children != null) {
      this.children.addAll(Arrays.asList(children));
    }
    return self();
  }

  public T with(Iterable<? extends DomContent> children) {
    for (DomContent child : children) {
      this.children.add(child);
    }
    return self();
  }

  public T with(Stream<? extends DomContent> children) {
    children.forEachOrdered(this.children::add);
    return self();
  }

  public T condWith(boolean condition, DomContent... children) {
    return condition ? with(children) : self();
  }

  public T withText(String text) {
    children.add(new Text(text));
    return self();
  }

  public int getNumChildren() {
    return children.size();
  }

  @Override boolean isVoid() {
    return false;
  }
}
