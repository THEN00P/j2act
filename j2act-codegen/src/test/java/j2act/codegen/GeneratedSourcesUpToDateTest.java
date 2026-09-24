package j2act.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The checked-in tag API must be exactly what the generator produces from the vendored data. */
class GeneratedSourcesUpToDateTest {

  private static final Path ROOT = Paths.get("").toAbsolutePath().getParent();

  @Test
  void checkedInSourcesMatchTheGenerator() throws IOException {
    Map<String, String> expected = Generate.generate(Model.load());
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, String> file : expected.entrySet()) {
      Path path = ROOT.resolve(file.getKey());
      if (!Files.exists(path) || !normalize(Files.readString(path, StandardCharsets.UTF_8)).equals(file.getValue())) {
        stale.add(file.getKey());
      }
    }
    try (Stream<Path> tags = Files.list(ROOT.resolve(Generate.TAGS))) {
      List<String> extra = tags
        .map(p -> Generate.TAGS + p.getFileName())
        .filter(p -> !expected.containsKey(p))
        .collect(Collectors.toList());
      stale.addAll(extra);
    }
    assertTrue(stale.isEmpty(), "stale generated sources " + stale
      + "; run ./mvnw -pl j2act-codegen compile exec:java");
  }

  @Test
  void generatesOneClassPerElementPlusGlobalsFactoriesAndDomInterfaces() {
    Model model = Model.load();
    assertEquals(model.elements.size() + 3, Generate.generate(model).size());
  }

  private static String normalize(String s) {
    return s.replace("\r\n", "\n");
  }
}
