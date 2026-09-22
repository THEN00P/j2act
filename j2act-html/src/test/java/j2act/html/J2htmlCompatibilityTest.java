package j2act.html;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Migration check: everything a j2html 1.6.0 user calls exists in j2act under the
 * same name, apart from the deliberate differences listed with their reasons. j2html
 * is a test-only dependency; the product API follows the HTML data, not j2html.
 */
class J2htmlCompatibilityTest {

  /** j2html TagCreator members we leave out on purpose. */
  static final Map<String, String> FACTORY_EXCLUSIONS = Map.of(
    "attrs", "attrs(\"#id.cls\") passes attributes as params; params stay children only (ADR 0005)",
    "rawHtml", "renamed unsafeHtml, the loud escape hatch (ADR 0008)",
    "keygen", "obsolete element, gone from the HTML data",
    "menuitem", "obsolete element, gone from the HTML data",
    "scriptWithInlineFile_min", "j2html's own minifier; minify in the asset build instead",
    "styleWithInlineFile_min", "j2html's own minifier; minify in the asset build instead");

  static final Set<String> CLASS_EXCLUSIONS = Set.of("KeygenTag", "MenuitemTag");

  @Test
  void everyJ2htmlFactoryExists() {
    Set<String> ours = publicStaticNames(TagCreator.class);
    Set<String> missing = new TreeSet<>(publicStaticNames(j2html.TagCreator.class));
    missing.removeAll(ours);
    missing.removeAll(FACTORY_EXCLUSIONS.keySet());
    assertEquals(Collections.emptySet(), missing);
  }

  @Test
  void everyJ2htmlTagClassHasItsBuilders() throws Exception {
    Map<String, Set<String>> gaps = new TreeMap<>();
    for (String simpleName : j2htmlTagClasses()) {
      if (CLASS_EXCLUSIONS.contains(simpleName)) {
        continue;
      }
      Class<?> theirs = Class.forName("j2html.tags.specialized." + simpleName);
      Class<?> ours;
      try {
        ours = Class.forName("j2act.html.tags." + simpleName);
      } catch (ClassNotFoundException e) {
        gaps.put(simpleName, Set.of("<missing class>"));
        continue;
      }
      Set<String> missing = new TreeSet<>(builderNames(theirs));
      missing.removeAll(builderNames(ours));
      missing.removeIf(name -> excludedBuilder(simpleName, name));
      if (!missing.isEmpty()) {
        gaps.put(simpleName, missing);
      }
    }
    assertEquals(Collections.emptyMap(), gaps);
  }

  /** Builders we leave out everywhere, with the reason. */
  static final Map<String, String> BUILDER_EXCLUSIONS = Map.of(
    "withCondAccessKey", "j2html casing slip next to its own withAccesskey; ours is withCondAccesskey",
    "isAutocomplete", "autocomplete takes tokens such as \"off\", not a boolean; use withAutocomplete");

  /** Builders we leave out on specific elements, because HTML dropped the attribute there. */
  static final Map<String, Set<String>> CLASS_BUILDER_EXCLUSIONS = Map.of(
    "ATag", Set.of("withMedia", "withCondMedia"),
    "AreaTag", Set.of("withMedia", "withCondMedia"),
    "MenuTag", Set.of("withType", "withCondType"));

  static boolean excludedBuilder(String simpleName, String name) {
    // withOnclick("js") and friends: inline JS strings; events are server handlers (ADR 0013).
    return name.startsWith("withOn") || name.startsWith("withCondOn") || BUILDER_EXCLUSIONS.containsKey(name)
      || CLASS_BUILDER_EXCLUSIONS.getOrDefault(simpleName, Set.of()).contains(name);
  }

  private static Set<String> builderNames(Class<?> type) {
    return Arrays.stream(type.getMethods())
      .filter(m -> !Modifier.isStatic(m.getModifiers()))
      .map(Method::getName)
      .filter(n -> n.startsWith("with") || n.startsWith("is"))
      .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> publicStaticNames(Class<?> type) {
    return Arrays.stream(type.getMethods())
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .map(Method::getName)
      .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> j2htmlTagClasses() throws Exception {
    URL anchor = j2html.TagCreator.class.getResource("TagCreator.class");
    try (JarFile jar = ((JarURLConnection) anchor.openConnection()).getJarFile()) {
      return jar.stream()
        .map(e -> e.getName())
        .filter(n -> n.startsWith("j2html/tags/specialized/") && n.endsWith("Tag.class") && !n.contains("$"))
        .map(n -> n.substring("j2html/tags/specialized/".length(), n.length() - ".class".length()))
        .collect(Collectors.toCollection(TreeSet::new));
    }
  }
}
