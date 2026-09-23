package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Socket abuse limits: token buckets per session chosen from identity, queue and frame bounds (ADR 0013). */
class RateLimitTest {

  static final class Clicks extends LiveComponent {
    private final State<Integer> n = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("clicks " + n.get()),
        T.button("add").onClick(e -> n.set(n.get() + 1)),
        T.input().withId("file").attr("type", "file").onChange(e -> upload.mutate(e.file()))));
    }

    private final Upload upload = upload();
  }

  /** Sends one click and returns whether the server accepted it. */
  private static boolean click(Harness h, String handler, int ack) {
    int from = h.conn.size();
    String a = String.valueOf(ack);
    h.engine.onMessage(h.conn, Json.object("t", "ev", "h", handler, "v", "", "a", a));
    return "1".equals(h.conn.await(from, m -> "ack".equals(m.get("t")) && a.equals(m.get("a"))).get("ok"));
  }

  private static Exchange as(String user) {
    return Exchange.of(Collections.singletonMap("user", user), Collections.emptyMap());
  }

  @Test
  void theBucketChosenFromIdentityDropsEventsPastItsBurst() {
    try (Harness h = new Harness(Clicks::new, b -> b
      .withIdentity(x -> x.cookie("user").filter(u -> !u.equals("anon"))
        .map(u -> AuthCtx.of(u, Collections.emptySet())).orElse(AuthCtx.anonymous()))
      .withRateLimit(auth -> auth.isAnonymous() ? RateLimit.perMinute(3) : RateLimit.perMinute(6)))) {
      h.load("/", as("anon"));
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      List<Boolean> accepted = new ArrayList<>();
      for (int i = 1; i <= 5; i++) {
        accepted.add(click(h, add, i));
      }
      assertEquals(List.of(true, true, true, false, false), accepted);
      assertEquals(2L, h.engine.stats().rateLimited.get(), "two dropped");
      assertFalse(h.conn.closed, "a short overflow only drops");
    }
    try (Harness h = new Harness(Clicks::new, b -> b
      .withIdentity(x -> AuthCtx.of("ada", Collections.emptySet()))
      .withRateLimit(auth -> auth.isAnonymous() ? RateLimit.perMinute(3) : RateLimit.perMinute(6)))) {
      h.load("/", as("ada"));
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      int ok = 0;
      for (int i = 1; i <= 8; i++) {
        ok += click(h, add, i) ? 1 : 0;
      }
      assertEquals(6, ok, "signed-in users get the looser limit");
    }
  }

  @Test
  void sustainedOverflowClosesTheSocket() {
    try (Harness h = new Harness(Clicks::new, b -> b.withRateLimit(auth -> RateLimit.perMinute(1)))) {
      h.load();
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      for (int i = 1; i <= 60 && !h.conn.closed; i++) {
        h.engine.onMessage(h.conn, Json.object("t", "ev", "h", add, "v", "", "a", String.valueOf(i)));
      }
      assertTrue(h.conn.closed);
    }
  }

  @Test
  void anOversizedFrameClosesTheSocket() {
    try (Harness h = new Harness(Clicks::new, b -> b.withMaxFrameSize(1000))) {
      h.load();
      h.connect();
      h.engine.onMessage(h.conn, Json.object("t", "ev", "h", "x", "v", "y".repeat(2000), "a", "1"));
      assertTrue(h.conn.closed);
    }
  }

  @Test
  void uploadChunksPastTheLimitAreToldToSlowDown() {
    try (Harness h = new Harness(Clicks::new, b -> b.withRateLimit(auth -> RateLimit.perMinute(2)))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.engine.onMessage(h.conn, Json.object("t", "ev", "h", Harness.handlerOn(h.html, "change", "file"), "v", "",
        "a", "1", "fi", "f", "fn", "a.txt", "fs", "3", "ft", "text/plain"));
      Map<String, String> up = h.conn.await(from, m -> "up".equals(m.get("t")));
      String token = up.get("u").substring(J2Act.UPLOAD_PATH.length());
      Exchange csrf = Exchange.of(Collections.emptyMap(), Collections.singletonMap("X-J2-Token", List.of(h.token)));
      int[] statuses = new int[3];
      for (int i = 0; i < 3; i++) {
        statuses[i] = h.engine.acceptChunk(token, i, new ByteArrayInputStream(new byte[] {'x'}), csrf).status();
      }
      assertEquals(200, statuses[0]);
      assertEquals(200, statuses[1]);
      assertEquals(429, statuses[2]);
    }
  }
}
