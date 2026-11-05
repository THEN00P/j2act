package j2act;

/** An input or change event carrying the element's current value, and for a file input the picked file. */
public final class ValueEvent {

  private final String value;
  private final UploadFile file;
  private final boolean undo;

  ValueEvent(String value) {
    this(value, null);
  }

  ValueEvent(String value, UploadFile file) {
    this(value, file, false);
  }

  ValueEvent(String value, UploadFile file, boolean undo) {
    this.value = value == null ? "" : value;
    this.file = file;
    this.undo = undo;
  }

  public String value() {
    return value;
  }

  /** The browser's undo or redo made this change: a handler that rewrites what is typed can leave it alone (ADR 0034). */
  public boolean isUndo() {
    return undo;
  }

  /** The file picked in a file input, for upload().mutate(file); null otherwise or when cleared. */
  public UploadFile file() {
    return file;
  }
}
