package j2act;

/** A keydown on an element with onKeyDown: the key, modifier state and the element's value. */
public final class KeyEvent {

  private final String key;
  private final String value;
  private final boolean ctrl;
  private final boolean shift;
  private final boolean alt;
  private final boolean meta;

  KeyEvent(String key, String value, boolean ctrl, boolean shift, boolean alt, boolean meta) {
    this.key = key == null ? "" : key;
    this.value = value == null ? "" : value;
    this.ctrl = ctrl;
    this.shift = shift;
    this.alt = alt;
    this.meta = meta;
  }

  /** The DOM key name, e.g. "Enter", "Escape", "a". */
  public String key() {
    return key;
  }

  /** The element's value when the key went down, for inputs. */
  public String value() {
    return value;
  }

  public boolean ctrl() {
    return ctrl;
  }

  public boolean shift() {
    return shift;
  }

  public boolean alt() {
    return alt;
  }

  public boolean meta() {
    return meta;
  }
}
