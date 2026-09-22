package j2act.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Elements and attributes merged from the vendored data: VS Code's HTML data for
 * what exists and its docs, MDN's compat data for deprecation and MDN links, and
 * overrides.json for the few places the data disagrees with the HTML standard.
 * Inline on* event attributes are dropped: server handlers replace them (ADR 0013).
 */
final class Model {

  static final class Attribute {
    final String name;
    final boolean bool;
    final boolean valueless;
    final String valueSet;
    final String description;
    final String mdn;
    final boolean deprecated;
    final boolean experimental;
    final Baseline baseline;

    Attribute(String name, String valueSet, boolean valueless, String description, String mdn,
              boolean deprecated, boolean experimental, Baseline baseline) {
      this.name = name;
      this.valueSet = valueSet;
      this.bool = "v".equals(valueSet);
      this.valueless = valueless;
      this.description = description;
      this.mdn = mdn;
      this.deprecated = deprecated;
      this.experimental = experimental;
      this.baseline = baseline;
    }
  }

  static final class Element {
    final String name;
    final boolean isVoid;
    final String description;
    final String mdn;
    final boolean deprecated;
    final boolean experimental;
    final Baseline baseline;
    final List<Attribute> attributes = new ArrayList<>();

    Element(String name, boolean isVoid, String description, String mdn,
            boolean deprecated, boolean experimental, Baseline baseline) {
      this.name = name;
      this.isVoid = isVoid;
      this.description = description;
      this.mdn = mdn;
      this.deprecated = deprecated;
      this.experimental = experimental;
      this.baseline = baseline;
    }
  }

  /** Browser support from the Baseline project, as shipped in VS Code's data. */
  static final class Baseline {
    final String level;
    final String lowDate;
    final String highDate;

    Baseline(String level, String lowDate, String highDate) {
      this.level = level;
      this.lowDate = lowDate;
      this.highDate = highDate;
    }

    static Baseline of(JsonNode status) {
      if (status.isMissingNode() || !status.has("baseline")) {
        return null;
      }
      JsonNode b = status.get("baseline");
      String level = b.isBoolean() ? (b.asBoolean() ? "high" : "false") : b.asText();
      return new Baseline(level, status.path("baseline_low_date").asText(null),
        status.path("baseline_high_date").asText(null));
    }
  }

  final List<Attribute> globals = new ArrayList<>();
  final List<Element> elements = new ArrayList<>();
  final List<String> specElements = new ArrayList<>();
  final Set<String> bcdElements = new LinkedHashSet<>();
  final Set<String> bcdDeprecatedElements = new LinkedHashSet<>();

  static Model load() {
    ObjectMapper json = new ObjectMapper();
    JsonNode vscode = read(json, "data/html-data.json");
    JsonNode bcd = read(json, "data/bcd-html-status.json");
    JsonNode webref = read(json, "data/webref-html-elements.json");
    JsonNode overrides = read(json, "data/overrides.json");
    Model model = new Model();

    JsonNode bcdGlobals = bcd.path("globalAttributes");
    Set<String> globalNames = new LinkedHashSet<>();
    for (JsonNode g : vscode.path("globalAttributes")) {
      String name = g.path("name").asText();
      if (isInlineHandler(name)) {
        continue;
      }
      JsonNode status = bcdGlobals.path(name);
      JsonNode fix = overrides.path("globalAttributes").path(name);
      model.globals.add(new Attribute(name, fix.path("valueSet").asText(g.path("valueSet").asText(null)),
        fix.path("valueless").asBoolean(false), description(g),
        firstNonNull(reference(g), status.path("mdn").asText(null)),
        status.path("deprecated").asBoolean(false), status.path("experimental").asBoolean(false),
        Baseline.of(g.path("status"))));
      globalNames.add(name);
    }

    JsonNode bcdElements = bcd.path("elements");
    bcdElements.fieldNames().forEachRemaining(n -> {
      model.bcdElements.add(n);
      if (bcdElements.path(n).path("deprecated").asBoolean(false)) {
        model.bcdDeprecatedElements.add(n);
      }
    });
    for (JsonNode t : vscode.path("tags")) {
      String name = t.path("name").asText();
      JsonNode status = bcdElements.path(name);
      Element element = new Element(name, t.path("void").asBoolean(false), description(t),
        firstNonNull(reference(t), status.path("mdn").asText(null)),
        status.path("deprecated").asBoolean(false), status.path("experimental").asBoolean(false),
        Baseline.of(t.path("status")));
      JsonNode attrStatus = status.path("attributes");
      Set<String> seenAttributes = new LinkedHashSet<>();
      for (JsonNode a : t.path("attributes")) {
        String attr = a.path("name").asText();
        // The data lists a few attributes twice on one element; the first entry wins.
        if (isInlineHandler(attr) || globalNames.contains(attr) || !seenAttributes.add(attr)) {
          continue;
        }
        JsonNode s = attrStatus.path(attr);
        JsonNode fix = overrides.path("elements").path(name).path("attributes").path(attr);
        if (fix.path("exclude").asBoolean(false)) {
          continue;
        }
        element.attributes.add(new Attribute(attr, fix.path("valueSet").asText(a.path("valueSet").asText(null)),
          fix.path("valueless").asBoolean(false), description(a),
          firstNonNull(s.path("mdn").asText(null), element.mdn),
          s.path("deprecated").asBoolean(false), s.path("experimental").asBoolean(false),
          Baseline.of(a.path("status"))));
      }
      overrides.path("elements").path(name).path("attributes").fields().forEachRemaining(fix -> {
        if (fix.getValue().path("add").asBoolean(false) && seenAttributes.add(fix.getKey())) {
          JsonNode s = attrStatus.path(fix.getKey());
          element.attributes.add(new Attribute(fix.getKey(), fix.getValue().path("valueSet").asText(null),
            fix.getValue().path("valueless").asBoolean(false), fix.getValue().path("description").asText(null),
            firstNonNull(fix.getValue().path("mdn").asText(null), s.path("mdn").asText(element.mdn)),
            s.path("deprecated").asBoolean(false), s.path("experimental").asBoolean(false), null));
        }
      });
      model.elements.add(element);
    }
    model.elements.sort(Comparator.comparing(e -> e.name));
    webref.path("elements").forEach(e -> model.specElements.add(e.asText()));
    return model;
  }

  static boolean isInlineHandler(String attribute) {
    return attribute.startsWith("on");
  }

  private static String description(JsonNode node) {
    JsonNode d = node.path("description");
    if (d.isMissingNode() || d.isNull()) {
      return null;
    }
    return d.isObject() ? d.path("value").asText(null) : d.asText(null);
  }

  private static String reference(JsonNode node) {
    for (JsonNode ref : node.path("references")) {
      if (ref.has("url")) {
        return ref.path("url").asText();
      }
    }
    return null;
  }

  private static String firstNonNull(String a, String b) {
    return a != null ? a : b;
  }

  private static JsonNode read(ObjectMapper json, String resource) {
    try (InputStream in = Model.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing " + resource + "; run UpdateData");
      }
      return json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
