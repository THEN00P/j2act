package j2act;

import static j2act.Reactive.createMemo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0031: reads inside loading(...) stream down the response; reads outside it are waited for. */
class StreamingTest {

  static final Map<String, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();

  static CompletableFuture<String> load(String key) {
    return LOADS.computeIfAbsent(key, k -> new CompletableFuture<>());
  }

  static class Sink implements ServeResult.ChunkSink {
    final List<String> chunks = new CopyOnWriteArrayList<>();
    final AtomicInteger ends = new AtomicInteger();

    @Override public void write(String chunk) {
      chunks.add(chunk);
    }

    @Override public void end() {
      ends.incrementAndGet();
    }

    String all() {
      return String.join("", chunks);
    }
  }

  static final class Product extends LiveComponent {
    @Override protected View setup() {
      var product = createMemo(() -> load("product"));
      var reviews = createMemo(() -> load("reviews"));
      return () -> T.page(product.get(), T.div(
        T.span("product:" + product.get()),
        Reactive.loading(() -> T.span("reviews:" + reviews.get())).withFallback(T.span("loading reviews"))));
    }
  }

  @Test
  void aReadOutsideIsInTheShellAndAReadInsideFollowsDownTheResponse() {
    LOADS.clear();
    load("product").complete("shoe");
    try (Harness h = new Harness(Product::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      assertEquals(200, result.status());
      assertTrue(result.streams());
      assertTrue(result.html().contains("product:shoe"), result.html());
      assertTrue(result.html().contains("<title>shoe</title>"), result.html());
      assertTrue(result.html().contains("loading reviews"), result.html());
      assertFalse(result.html().contains("</html>"), "the closing tags wait: " + result.html());

      Sink sink = new Sink();
      result.streamTo(sink);
      assertEquals(0, sink.ends.get());

      load("reviews").complete("good");
      Harness.eventually(() -> sink.ends.get() == 1, "the stream ends once nothing is left to wait for");
      String rest = sink.all();
      assertTrue(rest.contains("reviews:good"), rest);
      assertTrue(rest.contains("<template id=\"j2t-"), rest);
      assertTrue(rest.contains("function j2s(a)"), "the swap helper, once: " + rest);
      assertTrue(rest.endsWith("</body></html>"), rest);
    }
  }

  @Test
  void nothingToWaitForIsAPlainResponse() {
    LOADS.clear();
    load("product").complete("shoe");
    load("reviews").complete("good");
    try (Harness h = new Harness(Product::new)) {
      Harness.eventually(() -> load("reviews").isDone(), "ready");
      ServeResult result = h.engine.serve("/", Exchange.empty());
      if (!result.streams()) {
        assertTrue(result.html().endsWith("</body></html>"), result.html());
        assertTrue(result.html().contains("reviews:good"), result.html());
      }
    }
  }

  @Test
  void aChunkWrittenBeforeTheSinkAttachesIsKept() {
    LOADS.clear();
    load("product").complete("shoe");
    try (Harness h = new Harness(Product::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      load("reviews").complete("good");
      Sink sink = new Sink();
      Harness.eventually(() -> {
        result.streamTo(sink);
        return sink.ends.get() == 1;
      }, "buffered until attached");
      assertTrue(sink.all().contains("reviews:good"), sink.all());
    }
  }

  @Test
  void pastTheDeadlineTheResponseClosesAndTheLoadArrivesOverTheSocket() {
    LOADS.clear();
    load("product").complete("shoe");
    try (Harness h = new Harness(Product::new, b -> b.withStreamDeadline(Duration.ofMillis(50)))) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      Sink sink = new Sink();
      result.streamTo(sink);
      Harness.eventually(() -> sink.ends.get() == 1, "the deadline closes the response");
      assertTrue(sink.all().endsWith("</body></html>"));
      assertFalse(sink.all().contains("reviews:"), "nothing arrived yet");

      h.html = result.html();
      h.sid = Harness.find(h.html, "name=\"j2-tab\" content=\"([^\"]+)\"");
      h.token = Harness.find(h.html, "name=\"j2-token\" content=\"([^\"]+)\"");
      h.connect();
      int from = h.conn.size();
      load("reviews").complete("good");
      h.awaitPatch(from, p -> p.contains("reviews:good"));
      assertFalse(sink.all().contains("reviews:"), "the closed response got nothing");
    }
  }

  @Test
  void theSwapHelperCarriesTheNonce() {
    LOADS.clear();
    load("product").complete("shoe");
    try (Harness h = new Harness(Product::new, b -> b.withCspNonce(e -> "abc123"))) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      Sink sink = new Sink();
      result.streamTo(sink);
      load("reviews").complete("good");
      Harness.eventually(() -> sink.ends.get() == 1, "done");
      assertTrue(sink.all().contains("<script nonce=\"abc123\">function j2s"), sink.all());
      assertTrue(sink.all().contains("<script nonce=\"abc123\">j2s("), sink.all());
    }
  }

  static final class BoundaryInATable extends LiveComponent {
    @Override protected View setup() {
      return () -> T.page("t", new CustomTag("table", Reactive.loading(T.span("x")).withFallback(T.span("..."))));
    }
  }

  @Test
  void aBoundaryWhereADivCannotStandSaysSo() {
    try (Harness h = new Harness(BoundaryInATable::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      assertEquals(500, result.status(), "the parser would move it out of the table, parting it from what it wraps");
    }
  }

  static final class FailsOutside extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo(() -> load("outside"));
      return () -> T.page("t", T.span("value:" + value.get()));
    }
  }

  static final class FailingChild extends ComponentTag {
    @Override protected View setup() {
      var value = createMemo(() -> load("child"));
      return () -> T.span("value:" + value.get());
    }
  }

  static final class FailsInAChild extends LiveComponent {
    @Override protected View setup() {
      return () -> T.page("t", T.div(new FailingChild()));
    }
  }

  private static ServeResult servedSoon(Harness h, Runnable meanwhile) throws Exception {
    CompletableFuture<ServeResult> served = CompletableFuture.supplyAsync(() -> h.engine.serve("/", Exchange.empty()));
    Thread.sleep(50);
    meanwhile.run();
    return served.get(5, TimeUnit.SECONDS);
  }

  @Test
  void aLoadFailingOutsideEveryBoundaryIsAnErrorResponse() throws Exception {
    LOADS.clear();
    try (Harness h = new Harness(FailsOutside::new)) {
      ServeResult result = servedSoon(h, () -> load("outside").completeExceptionally(new IllegalStateException("on purpose")));
      assertEquals(500, result.status());
    }
  }

  @Test
  void aLoadFailingInAChildOutsideEveryBoundaryIsAnErrorResponse() throws Exception {
    LOADS.clear();
    try (Harness h = new Harness(FailsInAChild::new)) {
      ServeResult result = servedSoon(h, () -> load("child").completeExceptionally(new IllegalStateException("on purpose")));
      assertEquals(500, result.status());
    }
  }

  @Test
  void aLoadOutsideEveryBoundaryThatNeverAnswersGivesUpAtTheDeadline() throws Exception {
    LOADS.clear();
    try (Harness h = new Harness(FailsOutside::new, b -> b.withShellDeadline(Duration.ofMillis(200)))) {
      ServeResult result = servedSoon(h, () -> { });
      assertEquals(504, result.status());
    }
  }

  @Test
  void aLoadOutsideEveryBoundaryThatLandsBeforeTheDeadlineStillServes() throws Exception {
    LOADS.clear();
    try (Harness h = new Harness(FailsOutside::new, b -> b.withShellDeadline(Duration.ofSeconds(4)))) {
      ServeResult result = servedSoon(h, () -> load("outside").complete("late"));
      assertEquals(200, result.status());
      assertTrue(result.html().contains("value:late"), result.html());
    }
  }

  static final class FailsInside extends LiveComponent {
    @Override protected View setup() {
      var failing = createMemo(() -> load("failing"));
      var landing = createMemo(() -> load("landing"));
      return () -> T.page("t", T.div(
        Reactive.errored(() -> Reactive.loading(() -> T.span("failing:" + failing.get())).withFallback(T.span("...")))
          .withFallback(err -> T.span("error boundary")),
        Reactive.loading(() -> T.span("landing:" + landing.get())).withFallback(T.span("..."))));
    }
  }

  @Test
  void aLoadFailingInsideABoundaryDoesNotHoldTheResponseOpen() {
    LOADS.clear();
    load("landing").complete("here");
    try (Harness h = new Harness(FailsInside::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      assertTrue(result.streams());
      Sink sink = new Sink();
      result.streamTo(sink);
      load("failing").completeExceptionally(new IllegalStateException("on purpose"));
      // Well before the stream deadline of 10 seconds, whose end this must not need.
      long deadline = System.currentTimeMillis() + 3000;
      while (sink.ends.get() == 0 && System.currentTimeMillis() < deadline) {
        Thread.yield();
      }
      assertEquals(1, sink.ends.get(), "the stream ends once nothing is left to wait for: " + sink.all());
      assertTrue(sink.all().contains("error boundary"), sink.all());
    }
  }

  @Test
  void aLoadFailingBeforeTheOtherBoundaryLandsEndsTheStreamWhenThatOneDoes() {
    LOADS.clear();
    try (Harness h = new Harness(FailsInside::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      Sink sink = new Sink();
      result.streamTo(sink);
      load("failing").completeExceptionally(new IllegalStateException("on purpose"));
      Harness.eventually(() -> sink.all().contains("error boundary"), "the boundary shows it");
      assertEquals(0, sink.ends.get(), "the other boundary is still on its way");
      load("landing").complete("here");
      long deadline = System.currentTimeMillis() + 3000;
      while (sink.ends.get() == 0 && System.currentTimeMillis() < deadline) {
        Thread.yield();
      }
      assertEquals(1, sink.ends.get(), sink.all());
      assertTrue(sink.all().contains("landing:here"), sink.all());
    }
  }

  static final class MissingInside extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo(() -> load("missing"));
      return () -> T.page("t", T.div(
        Reactive.errored(Reactive.loading(() -> T.span("value:" + value.get())).withFallback(T.span("...")))
          .withFallback(err -> T.span("error boundary"))));
    }
  }

  @Test
  void notFoundInAStreamedPartIsAnErrorAtItsBoundary() {
    LOADS.clear();
    try (Harness h = new Harness(MissingInside::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      assertEquals(200, result.status());
      assertTrue(result.streams());
      Sink sink = new Sink();
      result.streamTo(sink);
      load("missing").completeExceptionally(RouteException.notFound());
      Harness.eventually(() -> sink.all().contains("error boundary"), "the boundary shows it: " + sink.all());
    }
  }

  static final class TwoParts extends LiveComponent {
    static final java.util.concurrent.atomic.AtomicReference<String> RENDERED = new java.util.concurrent.atomic.AtomicReference<>();

    @Override protected View setup() {
      var first = createMemo(() -> load("first"));
      var second = createMemo(() -> load("second"));
      return () -> T.page("t", T.div(
        Reactive.loading(() -> T.span("first:" + first.get())).withFallback(T.span("waiting")),
        Reactive.loading(() -> {
          String value = second.get();
          RENDERED.set(value);
          return T.span("second:" + value);
        }).withFallback(T.span("waiting"))));
    }
  }

  @Test
  void aSlowClientDoesNotHoldUpTheTab() {
    LOADS.clear();
    TwoParts.RENDERED.set(null);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Sink slow = new Sink() {
      @Override public void write(String chunk) {
        if (chunk.contains("first:")) {
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        super.write(chunk);
      }
    };
    try (Harness h = new Harness(TwoParts::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      Thread request = new Thread(() -> result.streamTo(slow));
      request.start();
      load("first").complete("a");
      Harness.eventually(() -> slow.chunks.size() >= 1, "the helper went out");
      load("second").complete("b");
      Harness.eventually(() -> "b".equals(TwoParts.RENDERED.get()), "the tab renders on while the client is stuck");
      release.countDown();
      Harness.eventually(() -> slow.ends.get() == 1, "and the response ends when it has caught up");
      assertTrue(slow.all().contains("second:b"), slow.all());
    }
  }
}
