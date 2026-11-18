package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.View;

/** Serializable without a serialVersionUID of its own: a bean the enhancer tracks, and a page it cuts. */
public final class Saved implements java.io.Serializable {
  private String note;

  public Saved() {
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public static final class Page extends LiveComponent implements java.io.Serializable {
    @Override protected View setup() {
      var n = createSignal(0);
      return () -> html(head(title("saved")), body(button("more").onClick(e -> n.set(n.get() + 1)), span("n" + n.get())));
    }
  }
}
