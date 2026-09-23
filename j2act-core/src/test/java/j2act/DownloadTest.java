package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** download(): token handed over the socket, body streamed on the fetching thread (ADR 0012). */
class DownloadTest {

  static final class Export extends LiveComponent {
    private final State<String> filter = state("ad");
    private final Download<String> csv = download((String like, OutputStream out) -> {
      if ("boom".equals(like)) {
        throw new IllegalStateException("export broke");
      }
      out.write(("name\n" + like + "a\n").getBytes(StandardCharsets.UTF_8));
    })
      .withFileName("Übersicht 2026.csv")
      .withContentType("text/csv");

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("export " + csv.status() + (csv.isError() ? " " + csv.error().getMessage() : "")),
        T.button("export").onClick(e -> csv.mutate(filter.get())),
        T.button("broken").onClick(e -> csv.mutate("boom"))));
    }
  }

  private static String token(Harness h, int from) {
    String url = h.conn.await(from, m -> "dl".equals(m.get("t"))).get("u");
    assertTrue(url.startsWith(J2Act.DOWNLOAD_PATH), url);
    return url.substring(J2Act.DOWNLOAD_PATH.length());
  }

  @Test
  void pendingUntilTheBrowserFetchedTheLastByteAndTheTokenIsSingleUse() throws IOException {
    try (Harness h = new Harness(Export::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "export"));
      assertTrue(Harness.last(patches, "patch").contains("export PENDING"));
      String token = token(h, from);

      DownloadStream download = h.engine.claimDownload(token, Exchange.empty());
      assertNotNull(download);
      assertEquals("text/csv", download.contentType());
      assertEquals("attachment; filename=\"_bersicht 2026.csv\"; filename*=UTF-8''%C3%9Cbersicht%202026.csv",
        download.contentDisposition());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      download.writeTo(out);
      assertEquals("name\nada\n", out.toString("UTF-8"));
      h.awaitPatch(from, p -> p.contains("export SUCCESS"));

      assertNull(h.engine.claimDownload(token, Exchange.empty()), "a token works once");
    }
  }

  @Test
  void aThrowingWriterFailsTheMutation() {
    try (Harness h = new Harness(Export::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "broken"));
      DownloadStream download = h.engine.claimDownload(token(h, from), Exchange.empty());
      assertThrows(IOException.class, () -> download.writeTo(new ByteArrayOutputStream()));
      h.awaitPatch(from, p -> p.contains("export ERROR export broke"));
    }
  }

  @Test
  void anUnfetchedTokenExpiresAndFailsTheMutation() {
    try (Harness h = new Harness(Export::new, b -> b.withDownloadTtl(Duration.ofMillis(100)))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "export"));
      String token = token(h, from);
      h.awaitPatch(from, p -> p.contains("export ERROR download was not fetched in time"));
      assertNull(h.engine.claimDownload(token, Exchange.empty()));
    }
  }

  @Test
  void aFetchUnderAnotherIdentityIsRefused() {
    try (Harness h = new Harness(Export::new, b -> b.withIdentity(x -> x.cookie("user")
      .map(u -> AuthCtx.of(u, Collections.emptySet())).orElse(AuthCtx.anonymous())))) {
      h.load("/", Exchange.of(Collections.singletonMap("user", "ada"), Collections.emptyMap()));
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "export"));
      String token = token(h, from);
      Exchange mallory = Exchange.of(Collections.singletonMap("user", "mallory"), Collections.emptyMap());
      assertNull(h.engine.claimDownload(token, mallory));
      h.awaitPatch(from, p -> p.contains("export ERROR download fetched with a different identity"));
    }
  }
}
