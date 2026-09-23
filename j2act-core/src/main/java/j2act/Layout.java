package j2act;

/**
 * A component in a layout() route position. It renders the matched child, received as
 * a plain child node (ADR 0005). Shared layouts stay mounted, with their State, across
 * soft navigations between routes below them (ADR 0011).
 */
public abstract class Layout extends ComponentTag {

  DomContent content;

  @Override
  protected final Tag<?> render() {
    return render(content);
  }

  public abstract Tag<?> render(DomContent content);
}
