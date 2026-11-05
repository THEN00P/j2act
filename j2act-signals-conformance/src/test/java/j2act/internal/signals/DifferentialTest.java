package j2act.internal.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * js/fuzz.test.ts on the port against the log prepare.mjs got from running it on Solid's own source. Every frame of
 * every seed has to be the same. Solid's tests say the port does what they ask; this says it does what Solid does.
 */
class DifferentialTest {

  private static Map<String, List<String>> seeds(String log) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    List<String> current = null;
    for (String line : log.split("\\R")) {
      if (line.startsWith("=== seed ")) {
        current = new ArrayList<>();
        out.put(line, current);
      } else if (current != null && !line.isEmpty()) {
        current.add(line);
      }
    }
    return out;
  }

  @Test
  void thePortShowsWhatSolidShowsOnRandomScenarios() throws Exception {
    java.util.logging.LogManager.getLogManager().reset();
    Path module = Path.of("").toAbsolutePath();
    Path expected = module.resolve("target/fuzz-expected.txt");
    Path bundle = module.resolve("target/fuzz-port.js");
    ByteArrayOutputStream console = new ByteArrayOutputStream();
    List<Conformance.Result> results = new Conformance(module).runFile(bundle, console);
    for (Conformance.Result r : results) {
      assertEquals("passed", r.status, r.key() + ": " + r.message);
    }
    Map<String, List<String>> solid = seeds(Files.readString(expected, StandardCharsets.UTF_8));
    Map<String, List<String>> port = seeds(console.toString(StandardCharsets.UTF_8));
    assertFalse(solid.isEmpty(), "Solid's run logged nothing");
    assertEquals(solid.size(), port.size(), "seeds run");
    for (Map.Entry<String, List<String>> seed : solid.entrySet()) {
      List<String> s = seed.getValue();
      List<String> p = port.get(seed.getKey());
      for (int i = 0; i < Math.max(s.size(), p.size()); i++) {
        String a = i < s.size() ? s.get(i) : "<nothing>";
        String b = i < p.size() ? p.get(i) : "<nothing>";
        if (!a.equals(b)) {
          fail(seed.getKey() + " differs at line " + i + "\n  Solid: " + a + "\n  port:  " + b
            + "\n  after: " + s.subList(Math.max(0, i - 6), Math.min(i, s.size())));
        }
      }
    }
  }
}
