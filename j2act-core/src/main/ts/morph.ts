import { Idiomorph } from "idiomorph";
import type { Config } from "idiomorph";
import { isTextField, type Fields } from "./fields.ts";
import type { Pending } from "./pending.ts";

export type Morpher = ReturnType<typeof createMorpher>;

/** One operation on a list region's rows (ADR 0036): remove, insert after, move after, or patch. */
export type ListOp = { r: string } | { i: string; a: string | null; h: string } | { m: string; a: string | null }
  | { p: string; h: string };

/** Added to Idiomorph by vite.config.ts: morph() of what lies between two nodes of one parent (ADR 0036). */
const morphRange = (Idiomorph as unknown as {
  morphRange(start: Node, end: Node, content: Node, config: Config): void;
}).morphRange;

/**
 * Applies the server's patches with Idiomorph (ADR 0003), keeping what is the browser's: the
 * pending state of a blocked element, form state the render did not set (ADR 0013), a client
 * element's node and children (ADR 0022), and what the runtime added to the head.
 */
export function createMorpher(pending: Pending, syncClient: (el: Element, fresh: Element) => void, fields: Fields) {
  /** The last event the server had handled, from the patch being applied. */
  let handled = 0;
  /** Copies of what the page already has, standing in for a patch's stubs, by original: the morph leaves those alone. */
  const kept = new WeakMap<Node, Node>();
  /** A region's start marker by anchor, found again whenever it is gone (ADR 0036). */
  const regions = new Map<string, Comment>();

  /** The markers around the region with this anchor, or null when it is not on the page. */
  /**
   * HTML parsed as it would be where it goes: under an svg or math element the parser makes SVG or MathML elements,
   * which a template on its own would make HTML ones of, so the fragment is parsed inside such an element.
   */
  function parseIn(html: string, parent: Node | null): DocumentFragment {
    const ns = parent && parent.nodeType === 1 ? (parent as Element).namespaceURI : null;
    const wrap = ns === "http://www.w3.org/2000/svg" ? "svg" : ns === "http://www.w3.org/1998/Math/MathML" ? "math" : null;
    const parsed = document.createElement("template");
    if (!wrap) {
      parsed.innerHTML = html;
      return parsed.content;
    }
    parsed.innerHTML = "<" + wrap + ">" + html + "</" + wrap + ">";
    const outer = parsed.content.firstChild!;
    const content = document.createDocumentFragment();
    while (outer.firstChild) {
      content.appendChild(outer.firstChild);
    }
    return content;
  }

  function region(anchor: string): [Comment, Comment] | null {
    let start = regions.get(anchor);
    if (!start || !start.isConnected || start.data !== "j2:" + anchor) {
      regions.clear();
      const walker = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_COMMENT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const data = (node as Comment).data;
        if (data.startsWith("j2:")) {
          regions.set(data.slice(3), node as Comment);
        }
      }
      start = regions.get(anchor);
      if (!start) {
        return null;
      }
    }
    for (let node = start.nextSibling; node; node = node.nextSibling) {
      if (node.nodeType === 8 && (node as Comment).data === "/j2:" + anchor) {
        return [start, node as Comment];
      }
    }
    return null;
  }

  /** What the page has for this anchor: a component's element, or a region's nodes with their markers. */
  function live(anchor: string): Node[] | null {
    const el = document.querySelector('[data-j2-scope="' + anchor + '"]');
    if (el) {
      return [el];
    }
    const range = region(anchor);
    if (!range) {
      return null;
    }
    const nodes: Node[] = [];
    for (let node: Node = range[0]; node !== range[1]; node = node.nextSibling!) {
      nodes.push(node);
    }
    nodes.push(range[1]);
    return nodes;
  }

  /**
   * Puts in a copy of what the page has for each stub in a patch, a scope the server knows the page shows as it
   * renders now (ADR 0036). False when one is not on the page, which has then drifted from the server.
   */
  function expandStubs(root: Node): boolean {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
    const stubs: Comment[] = [];
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if ((node as Comment).data.startsWith("j2=")) {
        stubs.push(node as Comment);
      }
    }
    for (const stub of stubs) {
      const originals = live(stub.data.slice(3));
      if (!originals) {
        return false;
      }
      stub.replaceWith(...originals.map((original) => {
        const copy = original.cloneNode(true);
        kept.set(copy, original);
        return copy;
      }));
    }
    return true;
  }

  const callbacks: Config["callbacks"] = {
    beforeAttributeUpdated: (name, el) => {
      if (pending.has(el) && (name === "data-pending" || name === "aria-busy")) {
        return false;
      }
      // A text field's value is merged in beforeNodeMorphed (ADR 0034).
      if (name === "value" && isTextField(el)) {
        return false;
      }
      // Left alone while it has focus, having no text to merge.
      if (name === "value" && el === document.activeElement) {
        return false;
      }
      // Form state the render did not set is the user's: keep it (ADR 0013).
      if ((name === "value" || name === "checked" || name === "selected") && el.hasAttribute("data-j2-controlled")) {
        return el.getAttribute("data-j2-controlled")!.split(" ").indexOf(name) >= 0;
      }
      return true;
    },
    // A client element keeps its node; its children are the client's (ADR 0022).
    beforeNodeMorphed: (oldNode, newNode) => {
      // A stub's original: the page has it as the server renders it, and anything since is the browser's.
      if (kept.get(newNode) === oldNode) {
        return false;
      }
      if (oldNode.nodeType === 1 && newNode.nodeType === 1 && oldNode.hasAttribute("data-j2-client")
        && oldNode.getAttribute("data-j2-client") === (newNode as Element).getAttribute("data-j2-client")) {
        syncClient(oldNode, newNode as Element);
        return false;
      }
      if (oldNode.nodeType === 1 && newNode.nodeType === 1 && oldNode.tagName === (newNode as Element).tagName) {
        const fresh = newNode as HTMLInputElement;
        // The server's value for a text field it controls goes in by merging (ADR 0034).
        if (isTextField(oldNode) && (fresh.getAttribute("data-j2-controlled") ?? "").split(" ").indexOf("value") >= 0) {
          fields.reconcile(oldNode, fresh.value, handled);
          return true;
        }
        // Being typed in, its contents are the user's. Idiomorph's ignoreActiveValue would do this for any focused
        // element, and a click focuses its button, so a button's label would stop updating.
        if (oldNode === document.activeElement && oldNode.hasAttribute("contenteditable")) {
          fresh.innerHTML = oldNode.innerHTML;
        }
      }
      return true;
    }
  };
  const config: Config = { morphStyle: "outerHTML", callbacks: callbacks };
  const slotConfig: Config = { morphStyle: "innerHTML", callbacks: callbacks };
  // Head morphs leave alone what the runtime added there: client stylesheets.
  const headConfig: Config = {
    morphStyle: "innerHTML",
    callbacks: { beforeNodeRemoved: (node) => !(node.nodeType === 1 && node.hasAttribute("data-j2-keep")) }
  };

  function head(html: string): void {
    Idiomorph.morph(document.head, html, headConfig);
  }

  function body(fresh: HTMLElement): void {
    Idiomorph.morph(document.body, fresh, config);
  }

  return {
    head,
    body,

    /**
     * Morphs the component at anchor; false when it is not in the document. The tab root may
     * be a new component after a navigation, with a new anchor, so it replaces head and body.
     */
    patch(anchor: string, html: string, isRoot: boolean, eventsHandled?: number): boolean {
      if (eventsHandled !== undefined) {
        handled = eventsHandled;
      }
      const stubs = html.indexOf("<!--j2=") >= 0;
      if (isRoot) {
        const doc = new DOMParser().parseFromString(html, "text/html");
        if (stubs && !expandStubs(doc.body)) {
          return false;
        }
        document.documentElement.setAttribute("data-j2-scope", anchor);
        head(doc.head.innerHTML);
        body(doc.body);
        return true;
      }
      const el = document.querySelector('[data-j2-scope="' + anchor + '"]');
      if (!el) {
        const range = region(anchor);
        if (!range) {
          return false;
        }
        // A region: its markers stay, and what is between them is morphed.
        const content = parseIn(html, range[0].parentNode);
        if (content.firstChild?.nodeType === 8 && (content.firstChild as Comment).data === "j2:" + anchor) {
          content.firstChild.remove();
        }
        if (content.lastChild?.nodeType === 8 && (content.lastChild as Comment).data === "/j2:" + anchor) {
          content.lastChild.remove();
        }
        if (stubs && !expandStubs(content)) {
          return false;
        }
        morphRange(range[0], range[1], content, config);
        return true;
      }
      if (!stubs) {
        Idiomorph.morph(el, html, config);
        return true;
      }
      const content = parseIn(html, el.parentNode);
      if (!expandStubs(content)) {
        return false;
      }
      Idiomorph.morph(el, content.firstElementChild!, config);
      return true;
    },

    /**
     * A list region's rows, changed in place (ADR 0036): rows that stay are not touched, a moved row's nodes move with
     * their state (moveBefore where the browser has it, keeping focus where it has not), and a changed row is morphed
     * as its own patch. False when the list or a row it names is not on the page, which has then drifted.
     */
    list(anchor: string, ops: ListOp[], eventsHandled?: number): boolean {
      if (eventsHandled !== undefined) {
        handled = eventsHandled;
      }
      const list = region(anchor);
      if (!list) {
        return false;
      }
      /** The node before which what goes after `after`, or at the list's start, is put; false if that row is gone. */
      const place = (after: string | null): Node | null | false => {
        if (after === null) {
          return list[0].nextSibling;
        }
        const row = region(after);
        return row ? row[1].nextSibling : false;
      };
      for (const op of ops) {
        if ("r" in op) {
          const row = region(op.r);
          if (!row) {
            return false;
          }
          for (let node: Node | null = row[0]; node;) {
            const next: Node | null = node === row[1] ? null : node.nextSibling;
            node.parentNode!.removeChild(node);
            node = next;
          }
        } else if ("i" in op) {
          const at = place(op.a);
          if (at === false) {
            return false;
          }
          const content = parseIn(op.h, list[0].parentNode);
          if (op.h.indexOf("<!--j2=") >= 0 && !expandStubs(content)) {
            return false;
          }
          list[0].parentNode!.insertBefore(content, at);
        } else if ("m" in op) {
          const row = region(op.m);
          const at = place(op.a);
          if (!row || at === false) {
            return false;
          }
          const parent = list[0].parentNode as Element & { moveBefore?: (node: Node, child: Node | null) => void };
          const focused = document.activeElement;
          const nodes: Node[] = [];
          for (let node: Node = row[0]; ; node = node.nextSibling!) {
            nodes.push(node);
            if (node === row[1]) {
              break;
            }
          }
          for (const node of nodes) {
            if (typeof parent.moveBefore === "function") {
              parent.moveBefore(node, at);
            } else {
              parent.insertBefore(node, at);
            }
          }
          if (focused instanceof HTMLElement && focused !== document.activeElement && focused.isConnected) {
            focused.focus();
          }
        } else if (!this.patch(op.p, op.h, false)) {
          return false;
        }
      }
      return true;
    },

    /**
     * An attribute region's patch (ADR 0036): only the attributes it sets, each asked of the same callback a morph asks,
     * so what the browser keeps in a morph it keeps here. False when its element is not on the page.
     */
    attrs(anchor: string, values: Record<string, string | null>): boolean {
      const el = document.querySelector("[data-j2-a-" + anchor + "]");
      if (!el) {
        return false;
      }
      for (const name of Object.keys(values)) {
        const value = values[name];
        if (value === null) {
          if (el.hasAttribute(name) && callbacks.beforeAttributeUpdated!(name, el, "remove") !== false) {
            el.removeAttribute(name);
          }
        } else if (el.getAttribute(name) !== value && callbacks.beforeAttributeUpdated!(name, el, "update") !== false) {
          el.setAttribute(name, value);
        }
      }
      return true;
    },

    slot(live: Element, html: string): void {
      Idiomorph.morph(live, html, slotConfig);
    }
  };
}
