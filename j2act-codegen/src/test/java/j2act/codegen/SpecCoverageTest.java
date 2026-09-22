package j2act.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Element coverage against the HTML specification (webref), after j2html's compliance
 * test. Obsolete spec elements, those MDN marks deprecated or no longer tracks, are
 * the only ones allowed to be missing.
 */
class SpecCoverageTest {

  /** Elements we generate that come from specs other than HTML itself. */
  private static final Set<String> OUTSIDE_HTML_SPEC = Set.of(
    "fencedframe" // WICG Fenced Frame
  );

  @Test
  void everyLiveHtmlElementIsGenerated() {
    Model model = Model.load();
    Set<String> generated = names(model);
    Set<String> missing = new TreeSet<>();
    for (String element : model.specElements) {
      boolean obsolete = !model.bcdElements.contains(element) || model.bcdDeprecatedElements.contains(element);
      if (!obsolete && !generated.contains(element)) {
        missing.add(element);
      }
    }
    assertTrue(missing.isEmpty(), "spec elements without a generated tag: " + missing);
  }

  @Test
  void everyGeneratedElementIsInTheSpec() {
    Model model = Model.load();
    Set<String> unknown = new TreeSet<>(names(model));
    unknown.removeAll(model.specElements);
    unknown.removeAll(OUTSIDE_HTML_SPEC);
    assertEquals(Set.of(), unknown);
  }

  @Test
  void inlineEventAttributesAreNeverGenerated() {
    Model model = Model.load();
    assertTrue(model.globals.stream().noneMatch(a -> a.name.startsWith("on")));
    assertTrue(model.elements.stream().flatMap(e -> e.attributes.stream()).noneMatch(a -> a.name.startsWith("on")));
  }

  private static Set<String> names(Model model) {
    return model.elements.stream().map(e -> e.name).collect(Collectors.toCollection(TreeSet::new));
  }
}
