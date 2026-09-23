package j2act;

/** An input or change event carrying the element's current value, and for a file input the picked file. */
public final class ValueEvent {

  private final String value;
  private final UploadFile file;

  ValueEvent(String value) {
    this(value, null);
  }

  ValueEvent(String value, UploadFile file) {
    this.value = value == null ? "" : value;
    this.file = file;
  }

  public String value() {
    return value;
  }

  /** The file picked in a file input, for upload().mutate(file); null otherwise or when cleared. */
  public UploadFile file() {
    return file;
  }
}
