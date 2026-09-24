package j2act;

/** A pointerdown or pointerup: which kind of pointer, and where in the viewport. */
public final class PointerEvent {

  private final String pointerType;
  private final double x;
  private final double y;

  PointerEvent(String pointerType, double x, double y) {
    this.pointerType = pointerType == null ? "" : pointerType;
    this.x = x;
    this.y = y;
  }

  /** "mouse", "pen" or "touch". */
  public String pointerType() {
    return pointerType;
  }

  public double x() {
    return x;
  }

  public double y() {
    return y;
  }
}
