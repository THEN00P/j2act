package j2act;

/** A layout's view (ADR 0031): it renders the matched child, received as a plain child node. */
@FunctionalInterface
public interface LayoutView extends View {

  Tag<?> render(DomContent child);

  @Override default Tag<?> render() {
    throw new IllegalStateException("a layout's view renders with its matched child");
  }
}
