package j2act;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * What a page model's HTML becomes in a browser (ADR 0036): parsed by Validator.nu's parser, which Firefox's is
 * translated from, as Solid checks its templates with parse5. The page models splice strings, which no parser ever
 * sees; this says where the browser would build something else than the string reads: a region's markers that end up
 * under two parents, a marker, stub or attribute mark the client cannot reach, or a patch whose HTML parses into
 * other nodes where the client puts it than where a fresh load puts it.
 */
final class BrowserParse {

  static final String HTML = "http://www.w3.org/1999/xhtml";
  static final String SVG = "http://www.w3.org/2000/svg";
  static final String MATHML = "http://www.w3.org/1998/Math/MathML";

  private static final Set<String> RAW_TEXT = new HashSet<>(Arrays.asList("textarea", "title", "script", "style"));

  private BrowserParse() {
  }

  private static HtmlDocumentBuilder builder() {
    HtmlDocumentBuilder builder = new HtmlDocumentBuilder(XmlViolationPolicy.ALLOW);
    builder.setIgnoringComments(false);
    return builder;
  }

  static Document parse(String doc) {
    try {
      return builder().parse(new InputSource(new StringReader(doc)));
    } catch (SAXException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** What is wrong with this page as a browser would have it, or null. */
  static String problems(String doc) {
    List<String> found = new ArrayList<>();
    walk(parse(doc).getDocumentElement(), false, found);
    return found.isEmpty() ? null : String.join("\n", found);
  }

  private static void walk(Node node, boolean inTemplate, List<String> found) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.COMMENT_NODE) {
        String data = child.getNodeValue();
        if (data.startsWith("j2=")) {
          found.add("a stub is on the page: <!--" + data + "--> in " + path(node));
        } else if (data.startsWith("j2:") || data.startsWith("/j2:")) {
          if (inTemplate) {
            found.add("a region's marker is inside a template's content, out of the document: <!--" + data + "--> in "
              + path(node));
          }
          if (data.startsWith("j2:") && closing(child, data.substring(3)) == null) {
            found.add("the markers of " + data.substring(3) + " are not under one parent: it opens in " + path(node)
              + ", and the parser moved what follows");
          }
        }
      } else if (child.getNodeType() == Node.TEXT_NODE) {
        if (node.getNodeType() == Node.ELEMENT_NODE && RAW_TEXT.contains(node.getLocalName())
          && child.getNodeValue().contains("<!--j2")) {
          found.add("a marker or stub is text inside <" + node.getLocalName() + ">: " + child.getNodeValue());
        }
      } else if (child.getNodeType() == Node.ELEMENT_NODE) {
        Element element = (Element) child;
        if (inTemplate) {
          NamedNodeMap attributes = element.getAttributes();
          for (int i = 0; i < attributes.getLength(); i++) {
            String name = attributes.item(i).getNodeName();
            if (name.startsWith("data-j2-a-") || name.equals("data-j2-scope")) {
              found.add("<" + element.getLocalName() + " " + name + "> is inside a template's content, which the"
                + " client's patches do not reach");
            }
          }
        }
        walk(element, inTemplate || "template".equals(element.getLocalName()) && HTML.equals(element.getNamespaceURI()),
          found);
      }
    }
  }

  /** The region's closing marker, if it is a later sibling of its opening one. */
  static Node closing(Node opening, String anchor) {
    for (Node next = opening.getNextSibling(); next != null; next = next.getNextSibling()) {
      if (next.getNodeType() == Node.COMMENT_NODE && next.getNodeValue().equals("/j2:" + anchor)) {
        return next;
      }
    }
    return null;
  }

  private static String path(Node node) {
    StringBuilder out = new StringBuilder();
    for (Node n = node; n != null && n.getNodeType() == Node.ELEMENT_NODE; n = n.getParentNode()) {
      out.insert(0, "<" + n.getLocalName() + ">");
    }
    return out.length() == 0 ? "the document" : out.toString();
  }

  // ---- what a patch's HTML becomes where the client puts it

  /**
   * How the client parses a patch's HTML for a place under `parent` (morph.ts's parseIn): in a template, and in an svg
   * or math element where the parser would read it as SVG or MathML. `wrapped` false is idiomorph's own parse of a
   * whole element's patch, which is always a template's.
   */
  static DocumentFragment parseAsClient(String html, Element parent, boolean wrapped) {
    String ns = parent == null ? HTML : parent.getNamespaceURI();
    String local = parent == null ? "" : parent.getLocalName();
    String context = "template";
    String contextNs = HTML;
    if (wrapped && SVG.equals(ns) && !Arrays.asList("foreignObject", "desc", "title").contains(local)) {
      context = "svg";
      contextNs = SVG;
    } else if (wrapped && MATHML.equals(ns) && !Arrays.asList("mi", "mo", "mn", "ms", "mtext").contains(local)
      && !("annotation-xml".equals(local) && parent.getAttribute("encoding").matches("(?i)text/html|application/xhtml\\+xml"))) {
      context = "math";
      contextNs = MATHML;
    }
    try {
      return builder().parseFragment(new InputSource(new StringReader(html)), context, contextNs);
    } catch (SAXException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A node and what is below it, written so namespaces show: two that read the same are the same DOM. */
  static String canonical(Node node) {
    StringBuilder out = new StringBuilder();
    canonical(node, out);
    return out.toString();
  }

  private static void canonical(Node node, StringBuilder out) {
    switch (node.getNodeType()) {
      case Node.TEXT_NODE:
        out.append(node.getNodeValue());
        return;
      case Node.COMMENT_NODE:
        out.append("<!--").append(node.getNodeValue()).append("-->");
        return;
      case Node.ELEMENT_NODE:
        String ns = node.getNamespaceURI();
        String prefix = SVG.equals(ns) ? "svg:" : MATHML.equals(ns) ? "math:" : HTML.equals(ns) ? "" : "?:";
        out.append('<').append(prefix).append(node.getLocalName());
        NamedNodeMap attributes = node.getAttributes();
        List<String> sorted = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
          sorted.add(attributes.item(i).getNodeName() + "=\"" + attributes.item(i).getNodeValue() + "\"");
        }
        java.util.Collections.sort(sorted);
        for (String attribute : sorted) {
          out.append(' ').append(attribute);
        }
        out.append('>');
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
          canonical(child, out);
        }
        out.append("</").append(prefix).append(node.getLocalName()).append('>');
        return;
      default:
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
          canonical(child, out);
        }
    }
  }

  /** The element carrying data-j2-scope="anchor", or the opening marker of the region with that anchor, or null. */
  static Node find(Node node, String anchor) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.COMMENT_NODE && child.getNodeValue().equals("j2:" + anchor)) {
        return child;
      }
      if (child.getNodeType() == Node.ELEMENT_NODE) {
        if (anchor.equals(((Element) child).getAttribute("data-j2-scope"))) {
          return child;
        }
        Node found = find(child, anchor);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /**
   * After a patch of `anchor` with `html` (stubs expanded) put the page model at `doc`: whether the client, parsing
   * that HTML where it goes, builds what a fresh load of `doc` builds there. Null when it does, or when the patch's
   * place is not on the page; else what differs. `stubbed`: the patch came with stubs, so the client parsed it
   * itself rather than leaving it to idiomorph.
   */
  static String patchProblem(String doc, String anchor, String html, boolean stubbed) {
    Document page = parse(doc);
    Node at = find(page.getDocumentElement(), anchor);
    if (at == null) {
      return null;
    }
    Element parent = at.getParentNode() instanceof Element ? (Element) at.getParentNode() : null;
    StringBuilder loaded = new StringBuilder();
    String client;
    if (at.getNodeType() == Node.COMMENT_NODE) {
      Node end = closing(at, anchor);
      if (end == null) {
        return null; // problems() says so.
      }
      for (Node n = at.getNextSibling(); n != end; n = n.getNextSibling()) {
        loaded.append(canonical(n));
      }
      // The client takes the markers off what it parsed, and morphs the rest between its own.
      String inner = html;
      if (inner.startsWith("<!--j2:" + anchor + "-->")) {
        inner = inner.substring(("<!--j2:" + anchor + "-->").length());
      }
      if (inner.endsWith("<!--/j2:" + anchor + "-->")) {
        inner = inner.substring(0, inner.length() - ("<!--/j2:" + anchor + "-->").length());
      }
      client = canonical(parseAsClient(inner, parent, true));
    } else {
      loaded.append(canonical(at));
      client = canonical(parseAsClient(html, parent, stubbed));
    }
    if (client.equals(loaded.toString())) {
      return null;
    }
    return "the patch of " + anchor + " parses into other nodes where the client puts it than a fresh load gives:\n"
      + "  client: " + client + "\n  load:   " + loaded;
  }
}
