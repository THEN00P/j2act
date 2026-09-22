package j2act;

/** A void element such as input, meta or img. */
public class EmptyTag extends Tag<EmptyTag> {

  public EmptyTag(String name) {
    super(name);
  }

  @Override boolean isVoid() {
    return true;
  }
}
