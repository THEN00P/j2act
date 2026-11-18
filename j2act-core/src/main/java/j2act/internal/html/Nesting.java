package j2act.internal.html;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Where the HTML parser builds something other than the markup reads (ADR 0036): one table of its tree construction
 * rules, which the renderer asks whether a region's markers stay under one parent, and the processor asks at a view's
 * nesting. After Svelte's html-tree-validation.js (MIT, Copyright (c) 2016-2025 Svelte Contributors), with the rules
 * it leaves out where they move what follows (a heading in a heading, a cell's own scope, an implied tbody), taken from
 * the HTML standard's "in body", "in table", "in table body", "in row", "in cell" and "in head" insertion modes, and
 * checked against Validator.nu's parser in NestingTest. Names are local names of HTML elements, custom ones too, which
 * the parser treats as any element it has no rule for; SVG and MathML content is not covered.
 */
public final class Nesting {

  /** Elements whose content the parser reads as text: scripting on, as where j2act runs, so noscript too. */
  public static final Set<String> TEXT = set("textarea", "title", "script", "style", "iframe", "noscript", "noembed",
    "noframes", "xmp", "plaintext");

  /** What the parser does with a start tag, or with text when the name is null, at a place in the open elements. */
  public enum Repair {
    /** It closes an open element first: `at` and what is inside it end before the new element starts. */
    CLOSES,
    /** It goes into an element the parser makes for it under `at`: a tr's tbody, a td's tr, a col's colgroup. */
    WRAPS,
    /** It goes before the table `at`, out of the table element it stood in. */
    FOSTERS,
    /** Its tag is dropped and what is inside it stays where it is: a div in a select, a tr outside a table. */
    DROPS,
    /** It stays where it is, empty: what is inside it goes where it would without it, as a form's in a table. */
    EMPTIES
  }

  /** What the parser does, and the open element it does it at. */
  public static final class Fix {
    public final Repair repair;
    public final Open at;

    Fix(Repair repair, Open at) {
      this.repair = repair;
      this.at = at;
    }

    @Override public String toString() {
      return repair + " at <" + at.name + ">";
    }
  }

  /** The open elements, innermost first: each knows the one it is in. */
  public static final class Open {
    public final String name;
    public final Open up;

    private Open(String name, Open up) {
      this.name = name;
      this.up = up;
    }

    /** An element opened inside this one, or inside nothing when `up` is null. */
    public static Open push(Open up, String name) {
      return new Open(name.toLowerCase(java.util.Locale.ROOT), up);
    }

    /** Whether `other` is this one or an element this one is in. */
    public boolean within(Open other) {
      for (Open o = this; o != null; o = o.up) {
        if (o == other) {
          return true;
        }
      }
      return false;
    }

    @Override public String toString() {
      return (up == null ? "" : up + " ") + "<" + name + ">";
    }
  }

  private Nesting() {
  }

  private static Set<String> set(String... names) {
    return new HashSet<>(Arrays.asList(names));
  }

  private static final Set<String> HEADINGS = set("h1", "h2", "h3", "h4", "h5", "h6");

  /** Start tags that close a p in button scope ("in body"). */
  private static final Set<String> CLOSE_P = set("address", "article", "aside", "blockquote", "center", "details",
    "dialog", "dir", "div", "dl", "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "main", "menu", "nav",
    "ol", "p", "search", "section", "summary", "ul", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "listing", "form",
    "plaintext", "xmp", "table", "hr", "li", "dd", "dt");

  /** The standard's "has an element in scope": where looking for an open element stops. */
  private static final Set<String> SCOPE = set("applet", "caption", "html", "table", "td", "th", "marquee", "object",
    "template");

  /** The standard's special elements, at which looking for an li, dd or dt to close stops (but address, div and p). */
  private static final Set<String> SPECIAL = set("address", "applet", "area", "article", "aside", "base", "basefont",
    "bgsound", "blockquote", "body", "br", "button", "caption", "center", "col", "colgroup", "dd", "details", "dir", "div",
    "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4",
    "h5", "h6", "head", "header", "hgroup", "hr", "html", "iframe", "img", "input", "keygen", "li", "link", "listing",
    "main", "marquee", "menu", "meta", "nav", "noembed", "noframes", "noscript", "object", "ol", "p", "param", "plaintext",
    "pre", "script", "search", "section", "select", "source", "style", "summary", "table", "tbody", "td", "template",
    "textarea", "tfoot", "th", "thead", "title", "tr", "track", "ul", "wbr", "xmp");

  /** What the standard's "generate implied end tags" ends. */
  private static final Set<String> IMPLIED = set("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc");

  private static final Set<String> SECTIONS = set("tbody", "thead", "tfoot");
  private static final Set<String> TABLE_PARTS = set("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead",
    "tr");
  /** What a table, a table section or a row keeps as it is, besides what it makes a wrapper for. */
  private static final Set<String> IN_TABLE = set("caption", "colgroup", "tbody", "thead", "tfoot", "style", "script",
    "template");
  private static final Set<String> IN_HEAD = set("base", "basefont", "bgsound", "link", "meta", "title", "noscript",
    "noframes", "style", "script", "template");

  /**
   * What the parser does with a start tag `child` (null for text that is not only whitespace) with `open` the open
   * elements, or null when it puts it where the markup says: inside open.name.
   */
  public static Fix repair(String child, Open open) {
    if (open == null) {
      return null;
    }
    if (child != null) {
      child = child.toLowerCase(java.util.Locale.ROOT);
    }
    String parent = open.name;
    if (parent.equals("template") || TEXT.contains(parent)) {
      return null;
    }
    // Where the parser is in a table, a section of one, a row or a column group.
    if (parent.equals("table")) {
      if (child == null) {
        return new Fix(Repair.FOSTERS, open);
      }
      if (child.equals("tr") || child.equals("td") || child.equals("th") || child.equals("col")) {
        return new Fix(Repair.WRAPS, open);
      }
      if (child.equals("table")) {
        return new Fix(Repair.CLOSES, open);
      }
      if (child.equals("form")) {
        return formInTable(open);
      }
      // An input there stays only as type=hidden, which this does not see: it counts as fostered.
      return IN_TABLE.contains(child) ? null : new Fix(Repair.FOSTERS, open);
    }
    if (SECTIONS.contains(parent)) {
      if (child == null) {
        return new Fix(Repair.FOSTERS, table(open));
      }
      if (child.equals("tr") || child.equals("style") || child.equals("script") || child.equals("template")) {
        return null;
      }
      if (child.equals("td") || child.equals("th")) {
        return new Fix(Repair.WRAPS, open);
      }
      return TABLE_PARTS.contains(child) || child.equals("table") ? new Fix(Repair.CLOSES, open)
        : child.equals("form") ? formInTable(open) : new Fix(Repair.FOSTERS, table(open));
    }
    if (parent.equals("tr")) {
      if (child == null) {
        return new Fix(Repair.FOSTERS, table(open));
      }
      if (child.equals("td") || child.equals("th") || child.equals("style") || child.equals("script")
        || child.equals("template")) {
        return null;
      }
      return TABLE_PARTS.contains(child) || child.equals("table") ? new Fix(Repair.CLOSES, open)
        : child.equals("form") ? formInTable(open) : new Fix(Repair.FOSTERS, table(open));
    }
    if (parent.equals("colgroup")) {
      return child != null && (child.equals("col") || child.equals("template")) ? null : new Fix(Repair.CLOSES, open);
    }
    if (parent.equals("head")) {
      return child != null && IN_HEAD.contains(child) ? null : new Fix(Repair.CLOSES, open);
    }
    Open select = null;
    for (Open o = open; o != null && !o.name.equals("template"); o = o.up) {
      if (o.name.equals("select")) {
        select = o;
        break;
      }
    }
    if (select != null) {
      // "In select", as the parser this is checked with has it: newer ones keep more inside a select, which the
      // markers do not mind either, since only what it drops differs.
      if (child == null || child.equals("script") || child.equals("template")) {
        return null;
      }
      if (child.equals("option")) {
        return parent.equals("option") ? new Fix(Repair.CLOSES, open) : null;
      }
      if (child.equals("optgroup")) {
        Open group = parent.equals("option") && open.up != null && open.up.name.equals("optgroup") ? open.up : open;
        return parent.equals("option") || parent.equals("optgroup") ? new Fix(Repair.CLOSES, group) : null;
      }
      return child.equals("input") || child.equals("keygen") || child.equals("textarea") || child.equals("select")
        ? new Fix(Repair.CLOSES, select) : new Fix(Repair.DROPS, open);
    }
    if (child == null) {
      return null;
    }
    // "In body", and in a cell or a caption, which go by it but for table parts.
    if (TABLE_PARTS.contains(child)) {
      Open cell = cell(open);
      if (cell != null) {
        return new Fix(Repair.CLOSES, cell);
      }
      // Outside a table, the parser drops them.
      return new Fix(Repair.DROPS, open);
    }
    if (CLOSE_P.contains(child)) {
      Open p = inScope(open, "p", true);
      if (p != null) {
        return new Fix(Repair.CLOSES, p);
      }
    }
    if (HEADINGS.contains(child) && HEADINGS.contains(parent)) {
      return new Fix(Repair.CLOSES, open);
    }
    if (child.equals("li") || child.equals("dd") || child.equals("dt")) {
      for (Open o = open; o != null; o = o.up) {
        if (child.equals("li") ? o.name.equals("li") : o.name.equals("dd") || o.name.equals("dt")) {
          return new Fix(Repair.CLOSES, o);
        }
        if (SPECIAL.contains(o.name) && !o.name.equals("address") && !o.name.equals("div") && !o.name.equals("p")) {
          break;
        }
      }
    }
    if (child.equals("button")) {
      Open button = inScope(open, "button", false);
      if (button != null) {
        return new Fix(Repair.CLOSES, button);
      }
    }
    if (child.equals("a")) {
      // The adoption agency closes an a still open, up to the marker a cell, caption, template or object sets.
      for (Open o = open; o != null; o = o.up) {
        if (o.name.equals("a")) {
          return new Fix(Repair.CLOSES, o);
        }
        if (o.name.equals("td") || o.name.equals("th") || o.name.equals("caption") || o.name.equals("template")
          || o.name.equals("object") || o.name.equals("marquee") || o.name.equals("applet")) {
          break;
        }
      }
    }
    if (child.equals("form")) {
      // A form in a form is dropped, and its end tag ends the outer one; not while a template is open.
      Open form = null;
      boolean template = false;
      for (Open o = open; o != null; o = o.up) {
        form = form == null && o.name.equals("form") ? o : form;
        template |= o.name.equals("template");
      }
      if (form != null && !template) {
        return new Fix(Repair.CLOSES, form);
      }
    }
    if ((child.equals("option") || child.equals("optgroup")) && parent.equals("option")) {
      return new Fix(Repair.CLOSES, open);
    }
    if ((child.equals("rb") || child.equals("rtc") || child.equals("rt") || child.equals("rp"))
      && inScope(open, "ruby", false) != null) {
      // Implied end tags: what ends without one, up to the ruby, rtc kept for an rt or rp.
      Open last = null;
      for (Open o = open; o != null && IMPLIED.contains(o.name)
        && !(o.name.equals("rtc") && (child.equals("rt") || child.equals("rp"))); o = o.up) {
        last = o;
      }
      if (last != null) {
        return new Fix(Repair.CLOSES, last);
      }
    }
    if (child.equals("html") || child.equals("head") || child.equals("body") || child.equals("frameset")
      || child.equals("frame")) {
      return new Fix(Repair.DROPS, open);
    }
    return null;
  }

  private static boolean table(String name) {
    return name.equals("table") || SECTIONS.contains(name) || name.equals("tr") || name.equals("colgroup");
  }

  /** A form in a table stays empty, or is dropped when another is open or a template is. */
  private static Fix formInTable(Open open) {
    for (Open o = open; o != null; o = o.up) {
      if (o.name.equals("form") || o.name.equals("template")) {
        return new Fix(Repair.DROPS, open);
      }
    }
    return new Fix(Repair.EMPTIES, open);
  }

  /** The table a table part is in, where what the parser fosters goes before. */
  private static Open table(Open open) {
    for (Open o = open; o != null; o = o.up) {
      if (o.name.equals("table")) {
        return o;
      }
    }
    return open;
  }

  /** The cell or caption a table part start tag ends, if one is open in table scope. */
  private static Open cell(Open open) {
    for (Open o = open; o != null; o = o.up) {
      if (o.name.equals("td") || o.name.equals("th") || o.name.equals("caption")) {
        return o;
      }
      if (o.name.equals("table") || o.name.equals("template") || o.name.equals("html")) {
        return null;
      }
    }
    return null;
  }

  /** The open element of this name in scope (button scope: a button stops it too), or null. */
  private static Open inScope(Open open, String name, boolean buttonScope) {
    for (Open o = open; o != null; o = o.up) {
      if (o.name.equals(name)) {
        return o;
      }
      if (SCOPE.contains(o.name) || buttonScope && o.name.equals("button")) {
        return null;
      }
    }
    return null;
  }
}
