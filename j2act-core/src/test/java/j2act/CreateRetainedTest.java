package j2act;

import static j2act.Reactive.createRetained;
import static j2act.Reactive.createSignal;
import static j2act.RetainedPauseTest.pause;
import static j2act.RetainedStateTest.type;
import static j2act.RetainedStateTest.harness;
import static j2act.RetainedStateTest.remount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0031: createRetained in setup(), its keys without a name table, and save with restore. */
class CreateRetainedTest {

  static final class Named extends LiveComponent {
    @Override protected View setup() {
      var note = createRetained("note", "");
      return () -> T.page("n", T.div(
        T.span("note=" + note.get() + ";restored=" + note.restored() + ";"),
        T.input().withId("note").onChange(e -> note.set(e.value()))));
    }
  }

  @Test
  void aNamedValueComesBackAndKnowsIt() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(Named::new, storage, b -> { })) {
      assertTrue(h.load().contains("note=;restored=false;"), h.html);
      h.connect();
      type(h, "note", "draft");
      String old = h.token;
      pause(h, false);

      String html = h.load("/", remount(old));
      assertTrue(html.contains("note=draft;restored=true;"), html);
    }
  }

  static final class UnnamedDifferentTypes extends LiveComponent {
    @Override protected View setup() {
      var text = createRetained("");
      var count = createRetained(0);
      return () -> T.page("n", T.div(
        T.span("text=" + text.get() + ";count=" + count.get() + ";"),
        T.input().withId("text").onChange(e -> text.set(e.value())),
        T.input().withId("count").onChange(e -> count.set(Integer.parseInt(e.value())))));
    }
  }

  @Test
  void withoutANameDifferentTypesFallBackOnTheType() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(UnnamedDifferentTypes::new, storage, b -> { })) {
      h.load();
      h.connect();
      type(h, "text", "hi");
      type(h, "count", "7");
      String old = h.token;
      pause(h, false);
      String html = h.load("/", remount(old));
      assertTrue(html.contains("text=hi;count=7;"), html);
    }
  }

  static final class UnnamedSameType extends LiveComponent {
    @Override protected View setup() {
      var first = createRetained("");
      var second = createRetained("");
      return () -> T.page("n", T.div(
        T.span("first=" + first.get() + ";second=" + second.get() + ";"),
        T.input().withId("first").onChange(e -> first.set(e.value()))));
    }
  }

  @Test
  void twoOfOneTypeWithNoNameAreNotRetainedRatherThanGuessed() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(UnnamedSameType::new, storage, b -> { })) {
      h.load();
      h.connect();
      type(h, "first", "hi");
      String old = h.token;
      pause(h, false);
      String html = h.load("/", remount(old));
      assertTrue(html.contains("first=;second=;"), "not swapped, not restored: " + html);
    }
  }

  static final AtomicInteger RESTORES = new AtomicInteger();
  static final AtomicInteger SAVES = new AtomicInteger();

  static final class SavesTwoSignals extends LiveComponent {
    @Override protected View setup() {
      var title = createSignal("");
      var body = createSignal("");
      var draft = createRetained("draft", () -> {
        SAVES.incrementAndGet();
        return title.get() + "|" + body.get();
      }, saved -> {
        RESTORES.incrementAndGet();
        title.set(saved.split("\\|")[0]);
        body.set(saved.split("\\|")[1]);
      });
      return () -> T.page("n", T.div(
        draft.restored() ? T.span("came back") : null,
        T.span("title=" + title.get() + ";body=" + body.get() + ";"),
        T.input().withId("title").onChange(e -> title.set(e.value())),
        T.input().withId("body").onChange(e -> body.set(e.value()))));
    }
  }

  @Test
  void saveAndRestoreHoldTwoSignalsTogetherAndTheFirstHtmlShowsThem() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    RESTORES.set(0);
    try (Harness h = harness(SavesTwoSignals::new, storage, b -> { })) {
      String first = h.load();
      assertFalse(first.contains("came back"), first);
      assertEquals(0, RESTORES.get(), "a fresh page restores nothing");
      h.connect();
      type(h, "title", "T");
      type(h, "body", "B");
      String old = h.token;
      pause(h, false);

      String html = h.load("/", remount(old));
      assertEquals(1, RESTORES.get());
      assertTrue(html.contains("title=T;body=B;"), "already in the first HTML: " + html);
      assertTrue(html.contains("came back"), html);
    }
  }

  @TabScoped
  public static final class KeptInAStore extends Store {
    final Signal<String> value = createSignal("");
    final RetainedValue<String> kept = createRetained("kept", value::get, value::set);
  }

  /** It asks for the store in its view, so the store is made and restored while that view runs. */
  static final class AsksForItInItsView extends LiveComponent {
    @Override protected View setup() {
      return () -> {
        KeptInAStore store = Reactive.useTabStore(KeptInAStore.class);
        return T.page("n", T.div(
          T.span("value=" + store.value.get() + ";"),
          T.input().withId("value").onChange(e -> store.value.set(e.value()))));
      };
    }
  }

  @Test
  void aStoreFirstAskedForInAViewStillGetsItsValueBack() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(AsksForItInItsView::new, storage, b -> { })) {
      h.load();
      h.connect();
      type(h, "value", "kept");
      String old = h.token;
      pause(h, false);

      String html = h.load("/", remount(old));
      assertTrue(html.contains("value=kept;"), "already in the first HTML: " + html);
    }
  }

  static final class SavesUnchanged extends LiveComponent {
    @Override protected View setup() {
      var value = createSignal("x");
      var other = createSignal(0);
      createRetained("saved", () -> {
        SAVES.incrementAndGet();
        return value.get();
      }, restored -> value.set(restored));
      return () -> T.page("n", T.button("other " + other.get()).onClick(e -> other.set(other.get() + 1)));
    }
  }

  @Test
  void saveIsTrackedSoItRunsOnlyWhenWhatItReadChanges() {
    SAVES.set(0);
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(SavesUnchanged::new, storage, b -> { })) {
      String html = h.load();
      h.connect();
      int once = SAVES.get();
      h.click(Harness.clickOn(html, "other 0"));
      assertEquals(once, SAVES.get(), "nothing it read changed");
    }
  }
}
