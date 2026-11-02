package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.Prop;
import j2act.View;

public class SetupOnce extends ComponentTag {

  private final Prop<String> label = prop("");
  private String plain;
  private int clicks;

  public SetupOnce withLabel(String l) {
    label.set(l);
    return this;
  }

  public SetupOnce withPlain(String p) { // expect: withPlain writes plain field plain, which is lost
    plain = p;
    return this;
  }

  public SetupOnce withThisPlain(String p) { // expect: withThisPlain writes plain field plain, which is lost
    this.plain = p;
    return this;
  }

  public SetupOnce withLocal(String p) {
    String copy = p;
    copy = copy + "!";
    label.set(copy);
    return this;
  }

  @Override protected View setup() {
    var count = createSignal(0);
    var once = "Hi " + label.get(); // expect: label is read once during setup and never updates
    var meant = untrack(label);
    var followed = createMemo(() -> "Hi " + label.get());
    var user = createMemo(() -> async(() -> "ada"));
    var one = createRetained("one"); var two = createRetained("two"); // expect: two createRetained calls on one line
    return () -> div(
      loading(p(user.get())) // expect: user is read before loading(...) exists
        .withFallback(span("...")),
      errored(p(user.get())) // expect: user is read before errored(...) exists
        .withFallback(error -> span("failed")),
      loading(() -> p(user.get()))
        .withFallback(span("...")),
      span(once + meant + followed.get() + count.get() + one.get() + two.get() + clicks + plain),
      safely()
    );
  }

  private j2act.Tag<?> safely() {
    return span("");
  }

  /** Not a view: free to catch what it likes. */
  void notAView() {
    try {
      clicks++;
    } catch (Throwable t) {
      clicks = 0;
    }
  }

  static final class CatchesInView extends ComponentTag {
    @Override protected View setup() {
      var value = createSignal("v");
      return () -> {
        try {
          return p(value.get());
        } catch (Exception e) {
          return p("fine: an Exception cannot be what stops rendering");
        }
      };
    }
  }

  static final class CatchesTooMuch extends ComponentTag {
    @Override protected View setup() { // expect: catch (Error e) in a view also catches what stops rendering
      var value = createSignal("v");
      return () -> {
        try {
          return p(value.get());
        } catch (Error e) {
          return p("swallows it");
        }
      };
    }
  }
}
