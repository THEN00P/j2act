package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** upload(): metadata checked up front, bytes in resumable chunks, files owned and cleaned (ADR 0006, 0008). */
class UploadTest {

  static volatile Path target;
  static volatile String naming = "ok";

  static final class Avatar extends LiveComponent {
    private final Upload up = upload()
      .withTarget(target)
      .withNaming((original, ctx) -> "ok".equals(naming) ? ctx.username() + "_" + original : naming)
      .withAccept("image/*", ".txt")
      .withMaxFileSize("1KB");

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.input().withId("avatar").attr("type", "file").onChange(e -> up.mutate(e.file())),
        T.span("upload " + up.status() + " " + up.progress() + "%"
          + (up.isSuccess() ? " at " + up.data().path().getFileName() + " as " + up.data().contentType() : "")
          + (up.isError() ? " failed: " + up.error().getMessage() : ""))));
    }
  }

  static final class Scratch extends LiveComponent {
    private final Upload up = upload();

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.input().withId("avatar").attr("type", "file").onChange(e -> up.mutate(e.file())),
        T.span("upload " + up.status() + (up.isSuccess() ? " at " + up.data().path() : ""))));
    }
  }

  private static String pick(Harness h, String name, long size, String type) {
    int from = h.conn.size();
    h.fire(Harness.handlerOn(h.html, "change", "avatar"), "C:\\fakepath\\" + name,
      "fi", "f1", "fn", name, "fs", String.valueOf(size), "ft", type);
    Map<String, String> up = h.conn.since(from, m -> "up".equals(m.get("t"))).stream().findFirst().orElse(null);
    if (up == null) {
      return null;
    }
    assertEquals("f1", up.get("f"));
    assertTrue(up.get("u").startsWith(J2Act.UPLOAD_PATH));
    return up.get("u").substring(J2Act.UPLOAD_PATH.length());
  }

  private static Exchange csrf(Harness h, String... headers) {
    Map<String, List<String>> map = new java.util.LinkedHashMap<>();
    map.put("X-J2-Token", Collections.singletonList(h.token));
    for (int i = 0; i < headers.length; i += 2) {
      map.put(headers[i], Collections.singletonList(headers[i + 1]));
    }
    return Exchange.of(Collections.emptyMap(), map);
  }

  private static ChunkResult send(Harness h, String token, long offset, String bytes, Exchange exchange) {
    return h.engine.acceptChunk(token, offset, new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8)), exchange);
  }

  @Test
  void chunksResumeFromTheServersOffsetAndTheFileLandsUnderItsName(@TempDir Path dir) throws IOException {
    target = dir;
    naming = "ok";
    try (Harness h = new Harness(Avatar::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      String token = pick(h, "../../me.txt", 10, "text/plain");
      h.awaitPatch(from, p -> p.contains("upload PENDING 0%"));

      assertEquals(200, send(h, token, 0, "hello", csrf(h)).status());
      h.awaitPatch(from, p -> p.contains("upload PENDING 50%"));
      ChunkResult stale = send(h, token, 0, "hello", csrf(h));
      assertEquals(409, stale.status(), "a repeated chunk is told where to resume");
      assertEquals("{\"o\":5}", stale.json());
      ChunkResult last = send(h, token, 5, "world", csrf(h));
      assertEquals("{\"o\":10,\"done\":1}", last.json());

      h.awaitPatch(from, p -> p.contains("upload SUCCESS 100% at anonymous_me.txt as text/plain"));
      assertEquals("helloworld", new String(Files.readAllBytes(dir.resolve("anonymous_me.txt")), StandardCharsets.UTF_8));
      assertEquals(404, send(h, token, 10, "", csrf(h)).status(), "the token dies with the upload");
    }
  }

  @Test
  void restrictionsAreCheckedOnTheClaimsAndAgainOnTheBytes(@TempDir Path dir) throws IOException {
    target = dir;
    naming = "ok";
    try (Harness h = new Harness(Avatar::new, b -> b.withUploadDir(dir.resolve("parts")))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      assertEquals(null, pick(h, "huge.png", 4096, "image/png"));
      h.awaitPatch(from, p -> p.contains("failed: huge.png is larger than 1024 bytes"));
      from = h.conn.size();
      assertEquals(null, pick(h, "run.exe", 10, "image/png"), "the type comes from the extension when the JDK knows it");
      h.awaitPatch(from, p -> p.contains("failed: run.exe is not an accepted type"));

      from = h.conn.size();
      String token = pick(h, "liar.png", 3, "image/png");
      assertEquals(413, send(h, token, 0, "more than three", csrf(h)).status());
      h.awaitPatch(from, p -> p.contains("failed: upload chunk went past the declared size"));
      try (Stream<Path> parts = Files.list(dir.resolve("parts"))) {
        assertEquals(0, parts.count(), "the partial file is gone");
      }
    }
  }

  @Test
  void chunksNeedTheCsrfTokenAndTheSameOrigin(@TempDir Path dir) {
    target = dir;
    naming = "ok";
    try (Harness h = new Harness(Avatar::new)) {
      h.load();
      h.connect();
      String token = pick(h, "a.txt", 2, "text/plain");
      assertEquals(403, send(h, token, 0, "hi", Exchange.empty()).status());
      assertEquals(403, send(h, token, 0, "hi", csrf(h, "Host", "app.example", "Origin", "https://evil.example")).status());
      assertEquals(200, send(h, token, 0, "hi", csrf(h, "Host", "app.example", "Origin", "https://app.example")).status());
    }
  }

  @Test
  void aNameOutsideTheTargetFailsTheUpload(@TempDir Path dir) {
    target = dir.resolve("inside");
    naming = "../outside.txt";
    try (Harness h = new Harness(Avatar::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      String token = pick(h, "a.txt", 2, "text/plain");
      assertEquals(500, send(h, token, 0, "hi", csrf(h)).status());
      h.awaitPatch(from, p -> p.contains("failed: upload name escapes the target directory"));
      assertFalse(Files.exists(dir.resolve("outside.txt")));
    } finally {
      naming = "ok";
    }
  }

  @Test
  void aStalledUploadFailsAndDropsItsPart(@TempDir Path dir) throws IOException {
    target = dir;
    try (Harness h = new Harness(Avatar::new, b -> b.withUploadDir(dir.resolve("parts"))
      .withUploadIdleTimeout(Duration.ofMillis(100)))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      String token = pick(h, "slow.txt", 10, "text/plain");
      send(h, token, 0, "hel", csrf(h));
      h.awaitPatch(from, p -> p.contains("failed: upload stalled"));
      try (Stream<Path> parts = Files.list(dir.resolve("parts"))) {
        assertEquals(0, parts.count());
      }
    }
  }

  @Test
  void anUntargetedUploadIsDeletedWhenTheSessionEnds(@TempDir Path dir) throws Exception {
    try (Harness h = new Harness(Scratch::new, b -> b.withUploadDir(dir))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      String token = pick(h, "notes.txt", 2, "text/plain");
      send(h, token, 0, "hi", csrf(h));
      String patch = h.awaitPatch(from, p -> p.contains("upload SUCCESS")).get("h");
      Path stored = Arrays.stream(patch.split(" at ")[1].split("<")[0].split("\\s")).findFirst().map(Path::of).get();
      assertTrue(Files.exists(stored), stored.toString());
      assertTrue(stored.startsWith(dir.resolve("done")));

      h.engine.onMessage(h.conn, Json.object("t", "bye"));
      long end = System.currentTimeMillis() + Harness.TIMEOUT_MS;
      while (Files.exists(stored) && System.currentTimeMillis() < end) {
        Thread.sleep(20);
      }
      assertFalse(Files.exists(stored), "deleted with the session");
    }
  }
}
