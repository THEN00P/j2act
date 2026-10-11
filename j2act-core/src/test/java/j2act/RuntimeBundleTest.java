package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The checked-in runtime.js must be built from the TypeScript next to it (ADR 0028). Its banner
 * names a hash of the sources, so this notices an edit without a rebuild, with no Node needed;
 * CI rebuilds from scratch and compares the bytes.
 */
class RuntimeBundleTest {

  private static final Path BUNDLE = Paths.get("src/main/resources/META-INF/resources/_j2act/runtime.js");
  private static final String SOURCES = "src/main/ts";

  @Test
  void checkedInBundleMatchesItsSources() throws IOException {
    String bundle = new String(Files.readAllBytes(BUNDLE), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("sources: sha256-([0-9a-f]{64})").matcher(bundle);
    assertTrue(m.find(), "runtime.js has no source hash; run npm run build in j2act-core");
    assertEquals(sourceHash(), m.group(1),
      "runtime.js is stale: run npm install (once) and npm run build in j2act-core, and commit runtime.js");
  }

  /** Keep in step with sourceHash in vite.config.ts. */
  private static String sourceHash() throws IOException {
    List<String> files;
    try (Stream<Path> walk = Files.walk(Paths.get(SOURCES))) {
      files = walk
        .filter(p -> p.getFileName().toString().endsWith(".ts"))
        .map(p -> p.toString().replace('\\', '/'))
        .collect(Collectors.toCollection(ArrayList::new));
    }
    files.add("package-lock.json");
    files.add("vite.config.ts");
    files.sort(null);
    MessageDigest sha;
    try {
      sha = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    for (String file : files) {
      String text = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8).replace("\r\n", "\n");
      sha.update((file + "\n" + text + "\n").getBytes(StandardCharsets.UTF_8));
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : sha.digest()) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
