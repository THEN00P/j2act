package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.html.Nesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * ADR 0036: Nesting, the renderer's and the processor's table of where the HTML parser builds other than the markup
 * reads, against a parser, Validator.nu's. Random open elements, each where the table says the parser keeps it, and in
 * them a start tag or text between two comments, as a region's content stands between its markers: the table says the
 * parser repairs it exactly when the comments end up under two parents or what stood between them does not.
 */
class NestingTest {

  private static final List<String> ELEMENTS = Arrays.asList("div", "p", "span", "a", "button", "ul", "ol", "li", "dl",
    "dt", "dd", "table", "tbody", "thead", "tfoot", "tr", "td", "th", "caption", "colgroup", "select", "option",
    "optgroup", "form", "h1", "h2", "h3", "section", "ruby", "rt", "rp", "rb", "rtc", "pre", "label", "em", "b",
    "template", "my-el", "details", "summary", "fieldset", "legend", "nav", "header", "main", "blockquote", "figure",
    "figcaption", "address", "object", "menu", "center", "listing", "hgroup", "aside", "footer");
  // Not search: Validator.nu 1.4.16 is older than it, and the table closes a p for it as browsers now do. Not dialog,
  // which it counts among the special elements and the standard does not: the table goes by the standard, so an li
  // in a dialog in an li folds where it might not have to.
  private static final List<String> VOID = Arrays.asList("hr", "input", "img", "br", "col");
  private static final Set<String> VOIDS = new java.util.HashSet<>(VOID);

  @Test
  void theTableSaysWhereTheParserMovesWhatStandsBetweenTwoComments() {
    Random random = new Random(Long.getLong("nesting.seed", 1));
    int seeds = Integer.getInteger("nesting.seeds", 20000);
    List<String> wrong = new ArrayList<>();
    for (int i = 0; i < seeds && wrong.size() < 20; i++) {
      Nesting.Open open = Nesting.Open.push(null, "body");
      List<String> names = new ArrayList<>();
      int depth = 1 + random.nextInt(4);
      for (int d = 0; d < depth; d++) {
        String name = ELEMENTS.get(random.nextInt(ELEMENTS.size()));
        if (Nesting.repair(name, open) != null) {
          continue;
        }
        open = Nesting.Open.push(open, name);
        names.add(name);
      }
      int pick = random.nextInt(ELEMENTS.size() + VOID.size() + 3);
      String child = pick < ELEMENTS.size() ? ELEMENTS.get(pick)
        : pick < ELEMENTS.size() + VOID.size() ? VOID.get(pick - ELEMENTS.size()) : null;
      String problem = check(names, open, child);
      if (problem != null) {
        wrong.add(problem);
      }
    }
    assertTrue(wrong.isEmpty(), String.join("\n", wrong));
  }

  @Test
  void aRegionInTheHeadEndsItWithWhatTheHeadDoesNotTake() {
    Nesting.Open head = Nesting.Open.push(Nesting.Open.push(null, "html"), "head");
    assertEquals(null, Nesting.repair("title", head));
    assertEquals(Nesting.Repair.CLOSES, Nesting.repair("div", head).repair);
    assertEquals(Nesting.Repair.CLOSES, Nesting.repair(null, head).repair);
  }

  /** What is wrong with the table for `child` in these open elements, or null. */
  private static String check(List<String> names, Nesting.Open open, String child) {
    StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head></head><body>");
    for (String name : names) {
      html.append('<').append(name).append('>');
    }
    html.append("<!--a-->");
    if (child == null) {
      html.append("text");
    } else if (VOIDS.contains(child)) {
      html.append('<').append(child).append('>');
    } else {
      html.append('<').append(child).append(">x</").append(child).append('>');
    }
    html.append("<!--b-->");
    for (int i = names.size() - 1; i >= 0; i--) {
      html.append("</").append(names.get(i)).append('>');
    }
    html.append("</body></html>");
    Document doc = BrowserParse.parse(html.toString());
    Node a = comment(doc, "a");
    Node b = comment(doc, "b");
    Nesting.Fix fix = Nesting.repair(child, open);
    // What a dropped tag held stays only where its text would.
    boolean predicted = fix == null || fix.repair == Nesting.Repair.EMPTIES
      || fix.repair == Nesting.Repair.DROPS && (VOIDS.contains(child) || Nesting.repair(null, open) == null);
    String innermost = names.isEmpty() ? "body" : names.get(names.size() - 1);
    // A repair can move what came before too, as the adoption agency does for an a in an a.
    if (predicted && (a == null || b == null || !innermost.equals(a.getParentNode().getLocalName()))) {
      return "the parser did not keep the open elements " + open + ": " + BrowserParse.canonical(doc);
    }
    // Kept: the comments under one parent, and what stood between them still there, text where a tag was dropped.
    boolean kept = a != null && b != null && a.getParentNode() == b.getParentNode();
    if (kept) {
      boolean found = false;
      for (Node n = a.getNextSibling(); n != null && n != b; n = n.getNextSibling()) {
        found |= child == null || fix != null && fix.repair == Nesting.Repair.DROPS ? n.getNodeType() == Node.TEXT_NODE
          : child.equals(n.getLocalName());
      }
      kept = found || fix != null && fix.repair == Nesting.Repair.DROPS && VOIDS.contains(child);
    }
    if (kept != predicted) {
      return (child == null ? "text" : "<" + child + ">") + " in " + open + ": the table says " + fix + ", the parser "
        + (kept ? "keeps it between" : "moves it from between") + " the comments: " + BrowserParse.canonical(doc);
    }
    return null;
  }

  private static Node comment(Node node, String data) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.COMMENT_NODE && child.getNodeValue().equals(data)) {
        return child;
      }
      Node found = comment(child, data);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
