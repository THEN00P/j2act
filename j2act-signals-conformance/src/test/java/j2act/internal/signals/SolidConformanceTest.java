package j2act.internal.signals;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Solid's own test files against the port (ADR 0033). A test passes, or it needs something the port leaves
 * out on purpose (it names it when it reaches it), or it is in known-failures.txt with the reason. Anything
 * else fails here, and so does a listed test that now passes.
 */
class SolidConformanceTest {
  /** What a test may fail for reaching, with why it is not ported. docs/spikes/solid-port-tests.md says the same. */
  private static final Set<String> LEFT_OUT = Set.of(
    // JS proxies
    "merge", "seedLoadingValue", "registerFold",
    // parts of the store with no meaning for Java objects, or not ported yet
    "shallowStore", "markRaw", "storePath", "omit", "mergeView", "viewOf", "omitView", "storeIsShallow",
    "isStatic", "hasStaticKeys",

    // developer tooling
    "attribution", "OBSERVE", "DEV", "graphSize", "GRAPH_SIZE_WARN_AT", "setSignalIdentities", "setWriteHook",
    "asyncTailFlights", "enforceLoadingBoundary",
    // hydration
    "setSnapshotCapture", "markSnapshotScope", "clearSnapshots", "getNextChildId", "peekNextChildId",
    // bridging foreign reactive systems, which Java has none of
    "enableExternalSource", "_resetExternalSourceConfig",
    // reaching into modules by name rather than through the surface
    "signal", "settlePendingSource", "dirtyQueue");

  private static final Pattern LEFT_OUT_NAME = Pattern.compile("\\[left out\\] ([A-Za-z_$0-9]+)");

  @Test
  void solidsTestsPass() throws Exception {
    java.util.logging.LogManager.getLogManager().reset();
    Path module = Path.of("").toAbsolutePath();
    String filter = System.getProperty("solid.file", "");
    List<Conformance.Result> results = new Conformance(module).runAll(filter);
    Map<String, String> known = known(module.resolve("src/test/resources/known-failures.txt"));
    Set<String> wholeFiles = new TreeSet<>();
    known.keySet().stream().filter(k -> k.endsWith(" :: *")).forEach(k -> wholeFiles.add(k.substring(0, k.length() - 5)));

    int passed = 0;
    int leftOut = 0;
    int listed = 0;
    List<String> wrong = new ArrayList<>();
    Set<String> stillFailing = new TreeSet<>();
    for (Conformance.Result r : results) {
      boolean isKnown = known.containsKey(r.key()) || wholeFiles.contains(r.file);
      if ("passed".equals(r.status)) {
        passed++;
        if (known.containsKey(r.key())) {
          wrong.add("now passes, take it off the list: " + r.key());
        }
      } else if ("failed".equals(r.status)) {
        Matcher m = LEFT_OUT_NAME.matcher(String.valueOf(r.message));
        if (m.find()) {
          leftOut++;
          if (!LEFT_OUT.contains(m.group(1))) {
            wrong.add("reaches " + m.group(1) + ", not ported and not listed as left out: " + r.key());
          }
        } else if (isKnown) {
          listed++;
          stillFailing.add(r.key());
        } else {
          wrong.add("FAILED " + r.key() + "\n    " + r.message);
        }
      }
    }
    if (filter.isEmpty()) {
      for (String key : known.keySet()) {
        if (!key.endsWith(" :: *") && !stillFailing.contains(key)) {
          wrong.add("listed but did not run, or needs a left-out name now: " + key);
        }
      }
    }
    System.out.println("Solid's tests: " + passed + " pass, " + leftOut + " need something left out, " + listed + " listed");
    assertTrue(passed > 0 || !filter.isEmpty(), "nothing ran");
    assertTrue(wrong.isEmpty(), () -> wrong.size() + " tests differ from the expected result:\n" + String.join("\n", wrong));
  }

  private static Map<String, String> known(Path file) throws IOException {
    Map<String, String> out = new HashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      int tab = line.indexOf('\t');
      out.put(tab < 0 ? line : line.substring(0, tab), tab < 0 ? "" : line.substring(tab + 1));
    }
    return out;
  }
}
