import { Idiomorph } from "idiomorph";
import type { Config } from "idiomorph";
import type { Pending } from "./pending.ts";

export type Morpher = ReturnType<typeof createMorpher>;

/** How a patch went: applied, its anchor is not in the document, or an element it kept is not. */
export type PatchResult = "applied" | "gone" | "resync";

/**
 * Applies the server's patches with Idiomorph (ADR 0003), keeping what is the browser's: the
 * pending state of a blocked element, form state the render did not set (ADR 0013), a client
 * element's node and children (ADR 0022), and what the runtime added to the head.
 */
export function createMorpher(pending: Pending, syncClient: (el: Element, fresh: Element) => void) {
  /** Copies standing in for kept elements, and the live element each copies. */
  const kept = new WeakMap<Node, Element>();

  /**
   * The live element under root of each unchanged child in fresh (ADR 0031), or null when one is
   * missing there: one client code moved elsewhere would otherwise be copied, and the page would
   * show two elements for one component.
   */
  function keptElements(root: Element, fresh: ParentNode): [Element, Element][] | null {
    const found: [Element, Element][] = [];
    for (const stub of Array.from(fresh.querySelectorAll("[data-j2-unchanged]"))) {
      const live = root.querySelector('[data-j2-scope="' + stub.getAttribute("data-j2-unchanged") + '"]');
      if (!live) {
        return null;
      }
      found.push([stub, live]);
    }
    return found;
  }

  /**
   * An unchanged child arrives as an empty element marked data-j2-unchanged: a copy of the element
   * the page shows for it takes its place, so the morph leaves that element as it is.
   */
  function keep(found: [Element, Element][]): void {
    for (const [stub, live] of found) {
      const copy = live.cloneNode(true) as Element;
      kept.set(copy, live);
      stub.replaceWith(copy);
    }
  }

  /**
   * Idiomorph matches elements by id alone, so a component that moved would get another one's
   * content morphed into its node, and the user's input and focus with it. Each component in fresh
   * and its live element under root get a shared temporary id, so Idiomorph moves the node instead.
   * Returns what untag needs after the morph: each anchor, the id its render gave it and its temporary id.
   */
  function pair(root: Element, fresh: ParentNode): [string, string, string][] {
    const tagged: [string, string, string][] = [];
    // A fresh prefix per patch, so no id the page has can collide with ours.
    const prefix = "j2k" + Math.random().toString(36).slice(2, 10) + "-";
    for (const el of Array.from(fresh.querySelectorAll("[data-j2-scope],[data-j2-unchanged]"))) {
      const anchor = el.getAttribute("data-j2-scope") ?? el.getAttribute("data-j2-unchanged")!;
      const live = root.querySelector('[data-j2-scope="' + anchor + '"]');
      if (!live) {
        continue;
      }
      // A kept element keeps the id it has; a rendered one gets the render's.
      const authored = el.hasAttribute("data-j2-unchanged") ? live.id : el.id;
      if (authored && authored === live.id) {
        continue; // already matched by its own id
      }
      el.id = live.id = prefix + anchor;
      tagged.push([anchor, authored, el.id]);
    }
    return tagged;
  }

  /**
   * Gives each paired component its authored id back. Looked up by anchor, since a component whose
   * root tag changed was replaced rather than moved, and the page now shows the fresh element.
   */
  function untag(tagged: [string, string, string][]): void {
    for (const [anchor, authored, temporary] of tagged) {
      const el = document.querySelector('[data-j2-scope="' + anchor + '"]');
      if (el && el.id === temporary) {
        if (authored) {
          el.id = authored;
        } else {
          el.removeAttribute("id");
        }
      }
    }
  }

  const callbacks: Config["callbacks"] = {
    beforeAttributeUpdated: (name, el) => {
      if (pending.has(el) && (name === "data-pending" || name === "aria-busy")) {
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
      // A kept element facing itself: nothing in it changed.
      if (kept.get(newNode) === oldNode) {
        return false;
      }
      if (oldNode.nodeType === 1 && newNode.nodeType === 1 && oldNode.hasAttribute("data-j2-client")
        && oldNode.getAttribute("data-j2-client") === (newNode as Element).getAttribute("data-j2-client")) {
        syncClient(oldNode, newNode as Element);
        return false;
      }
      return true;
    }
  };
  const config: Config = { morphStyle: "outerHTML", ignoreActiveValue: true, callbacks: callbacks };
  const slotConfig: Config = { morphStyle: "innerHTML", ignoreActiveValue: true, callbacks: callbacks };
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
     * Morphs the component at anchor. The tab root may be a new component after a navigation,
     * with a new anchor, so it replaces head and body.
     */
    patch(anchor: string, html: string, isRoot: boolean): PatchResult {
      if (isRoot) {
        const doc = new DOMParser().parseFromString(html, "text/html");
        // Checked before pairing, so a rejected patch leaves every id as it was.
        const found = keptElements(document.body, doc.body);
        if (!found) {
          return "resync";
        }
        const tagged = pair(document.body, doc.body);
        keep(found);
        document.documentElement.setAttribute("data-j2-scope", anchor);
        head(doc.head.innerHTML);
        body(doc.body);
        untag(tagged);
        return "applied";
      }
      const el = document.querySelector('[data-j2-scope="' + anchor + '"]');
      if (!el) {
        return "gone";
      }
      const template = document.createElement("template");
      template.innerHTML = html;
      const found = keptElements(el, template.content);
      if (!found) {
        return "resync";
      }
      const tagged = pair(el, template.content);
      keep(found);
      const fresh = template.content.firstElementChild;
      if (fresh) {
        Idiomorph.morph(el, fresh, config);
      }
      untag(tagged);
      return "applied";
    },

    slot(live: Element, html: string): void {
      Idiomorph.morph(live, html, slotConfig);
    }
  };
}
