package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.DomContent;
import j2act.LiveComponent;
import j2act.Prop;
import j2act.View;
import java.util.concurrent.CompletableFuture;

/** A page as an app writes it: the enhancer cuts its child expressions into regions. */
public final class Board extends LiveComponent {

  public static int views;
  public static CompletableFuture<String> late = new CompletableFuture<>();

  public static final class Badge extends ComponentTag {
    private final Prop<String> text = prop("");

    public Badge withText(String t) {
      text.set(t);
      return this;
    }

    @Override protected View setup() {
      var clicks = createSignal(0);
      return () -> button("badge " + text.get() + " " + clicks.get()).onClick(e -> clicks.set(clicks.get() + 1));
    }
  }

  @Override protected View setup() {
    var name = createSignal("Ada");
    var count = createSignal(0);
    var show = createSignal(true);
    var slow = createMemo(() -> late);
    long big = 7L;
    double ratio = 0.5;
    int base = 3;
    return () -> {
      views++;
      int seen;
      return html(
        head(title("board")),
        body(
          button("rename").onClick(e -> name.set(name.get() + "!")),
          button("count " + count.get()).onClick(e -> count.set(count.get() + 1)),
          button("toggle").onClick(e -> show.set(!show.get())),
          p(span("hello " + name.get())),
          show.get() ? p("shown " + name.get()) : null,
          div(span("mixed " + big + " " + ratio + " " + base + " " + name.get())),
          p(label(name.get())),
          new Badge().withText(name.get()),
          div(loading(() -> span("slow " + slow.get())).withFallback(span("slow loading"))),
          p(guarded(count.get())),
          // Writes a local of the view: left in it, which runs again for it.
          p(span("seen " + (seen = count.get()) + " " + seen)),
          p(span(String.valueOf(count.get() * base)), span("static"))));
    };
  }

  private DomContent label(String n) {
    return span("label " + n);
  }

  private static DomContent guarded(int n) {
    DomContent shown;
    try {
      shown = span("guarded " + Integer.toString(n));
    } catch (RuntimeException e) {
      shown = span("failed");
    }
    return shown;
  }
}
