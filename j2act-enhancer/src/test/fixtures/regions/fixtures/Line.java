package fixtures;

/** A message as a shared room keeps it: the enhancer makes it track its own reads. */
public final class Line {
  private long id;
  private String author;
  private String text;

  public Line() {
  }

  public Line(long id, String author, String text) {
    this.id = id;
    this.author = author;
    this.text = text;
  }

  public long getId() {
    return id;
  }

  public String getAuthor() {
    return author;
  }

  public String getText() {
    return text;
  }
}
