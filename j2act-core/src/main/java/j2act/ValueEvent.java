package j2act;

/** An input or change event carrying the element's current value. */
public final class ValueEvent {

  private final String value;

  ValueEvent(String value) {
    this.value = value == null ? "" : value;
  }

  public String value() {
    return value;
  }
}
