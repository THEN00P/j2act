package j2act;

import static j2act.Reactive.async;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.refresh;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValueEqualityTest {
  static final class Entity {
    final long id;
    final String status;

    Entity(long id, String status) {
      this.id = id;
      this.status = status;
    }

    @Override public boolean equals(Object other) {
      return other instanceof Entity && ((Entity) other).id == id;
    }

    @Override public int hashCode() {
      return Long.hashCode(id);
    }
  }

  static final class AsyncPage extends LiveComponent {
    @Override protected View setup() {
      var loads = new AtomicInteger();
      var orders = createMemo(() -> async(() -> List.of(new Entity(1, "status" + loads.incrementAndGet()))));
      return () -> T.page("t", T.div(
        T.span(orders.get().get(0).status),
        T.button("again").onClick(e -> refresh(orders))));
    }
  }

  static final class SignalPage extends LiveComponent {
    @Override protected View setup() {
      var orders = createSignal(List.of(new Entity(1, "status1")));
      return () -> T.page("t", T.div(
        T.span(orders.get().get(0).status),
        T.button("again").onClick(e -> orders.set(List.of(new Entity(1, "status2"))))));
    }
  }

  static final class MemoPage extends LiveComponent {
    @Override protected View setup() {
      var version = createSignal(1);
      var orders = createMemo(() -> List.of(new Entity(1, "status" + version.get())));
      return () -> T.page("t", T.div(
        T.span(orders.get().get(0).status),
        T.button("again").onClick(e -> version.set(2))));
    }
  }

  private void reload(java.util.function.Supplier<LiveComponent> page) {
    try (Harness h = new Harness(page)) {
      String html = h.load();
      assertTrue(html.contains("status1"), html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "again"));
      h.awaitPatch(from, p -> p.contains("status2"));
    }
  }

  @Test void asyncMemoPublishesChangedEntityContents() {
    reload(AsyncPage::new);
  }

  @Test void signalPublishesChangedEntityContents() {
    reload(SignalPage::new);
  }

  @Test void memoPublishesChangedEntityContents() {
    reload(MemoPage::new);
  }
}
