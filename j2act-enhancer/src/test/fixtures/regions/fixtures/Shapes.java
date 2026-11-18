package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.AsyncSupplier;
import j2act.LiveComponent;
import j2act.View;
import java.util.concurrent.CompletableFuture;

/**
 * Pages whose shape the enhancer has to keep, each driven by its "more" button and, for Revealed, by its futures:
 * plain and enhanced they show the same, and the browser parses the enhanced one as the client needs.
 */
public final class Shapes {

  private Shapes() {
  }

  /** An attribute inside a template, read by nothing else. */
  public static final class TemplateAttr extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(0);
      return () -> html(head(title("t")), body(button("more").onClick(e -> n.set(n.get() + 1)),
        template(span("x").withClass("c" + n.get()))));
    }
  }

  /** html().with(...) rather than html(...). */
  public static final class HtmlWith extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(0);
      return () -> html().with(head(title("t" + n.get())), body(button("more").onClick(e -> n.set(n.get() + 1)),
        span("n" + n.get())));
    }
  }

  /** What the parser reads as text, below an element of it or spelled in capitals. */
  public static final class RawText extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(0);
      return () -> html(head(title("t")), body(button("more").onClick(e -> n.set(n.get() + 1)),
        noscript(span(text("n" + n.get()))),
        tag("TEXTAREA").with(text("n" + n.get()))));
    }
  }

  public static CompletableFuture<String> first;
  public static CompletableFuture<String> second;

  /** A sequential reveal: the second boundary waits for the first. */
  public static final class Revealed extends LiveComponent {
    @Override protected View setup() {
      AsyncSupplier<String> a = () -> first;
      AsyncSupplier<String> b = () -> second;
      var one = createMemo(a);
      var two = createMemo(b);
      return () -> html(head(title("t")), body(reveal(
        loading(() -> span("one:" + one.get())).withFallback(span("wait one")),
        loading(() -> span("two:" + two.get())).withFallback(span("wait two")))));
    }
  }
}
