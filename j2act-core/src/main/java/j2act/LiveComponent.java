package j2act;

/** A component with a public render(), used for pages. Pages return html(head(...), body(...)). */
public abstract class LiveComponent extends ComponentTag {

  @Override
  public abstract Tag<?> render();
}
