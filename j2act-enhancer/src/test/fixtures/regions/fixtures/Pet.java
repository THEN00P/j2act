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

  /** Equal by kind, as an entity is by its id: a renamed pet is still equal to what it was. */
  @Override public boolean equals(Object o) {
    return o instanceof Pet && java.util.Objects.equals(kind, ((Pet) o).kind);
  }

  @Override public int hashCode() {
    return java.util.Objects.hashCode(kind);
  }
}
