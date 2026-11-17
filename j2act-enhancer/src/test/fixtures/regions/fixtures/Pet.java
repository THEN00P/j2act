package fixtures;

/** A bean but for its superclass's field, whose reads the enhancer would not see: it stays as it is. */
public final class Pet extends Named {
  private String kind;

  public Pet() {
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }
}
