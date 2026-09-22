package j2act;

/** Any element by name, e.g. a web component: tag("my-chart"). */
public final class CustomTag extends ContainerTag<CustomTag> {

  public CustomTag(String name, DomContent... children) {
    super(name);
    with(children);
  }
}
