package fixtures;

/** A superclass with a field of its own: never tracked, so nothing below it is either. */
public abstract class Named {
  private String name;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
