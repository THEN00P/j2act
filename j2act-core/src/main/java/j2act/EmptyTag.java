package j2act;

/** A void element such as input, meta or img. */
public abstract class EmptyTag<T extends EmptyTag<T>> extends Tag<T> {

  protected EmptyTag(String name) {
    super(name);
  }

  @Override boolean isVoid() {
    return true;
  }
}
