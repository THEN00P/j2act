package j2act;

/** A keydown on an element with onKeyDown: the key, modifier state and the element's value. */
public final class KeyEvent {

  private final String key;
  private final String value;
  private final String modifiers;

  KeyEvent(String key, String value, String modifiers) {
    this.key = key == null ? "" : key;
    this.value = value == null ? "" : value;
    this.modifiers = modifiers == null ? "" : modifiers;
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
    return modifiers.indexOf('c') >= 0;
  }

  public boolean shift() {
    return modifiers.indexOf('s') >= 0;
  }

  public boolean alt() {
    return modifiers.indexOf('a') >= 0;
  }

  public boolean meta() {
    return modifiers.indexOf('m') >= 0;
  }
}
