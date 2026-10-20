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
   * An unchanged child arrives as an empty element marked data-j2-unchanged (ADR 0031): a copy of
   * the element the page shows for it takes its place, so the morph leaves that element as it is.
   * False when one is missing.
   */
  function keep(fresh: ParentNode): boolean {
    for (const stub of Array.from(fresh.querySelectorAll("[data-j2-unchanged]"))) {
      const live = document.querySelector('[data-j2-scope="' + stub.getAttribute("data-j2-unchanged") + '"]');
      if (!live) {
        return false;
      }
      const copy = live.cloneNode(true) as Element;
      kept.set(copy, live);
      stub.replaceWith(copy);
    }
    return true;
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
        if (!keep(doc.body)) {
          return "resync";
        }
        document.documentElement.setAttribute("data-j2-scope", anchor);
        head(doc.head.innerHTML);
        body(doc.body);
        return "applied";
      }
      const el = document.querySelector('[data-j2-scope="' + anchor + '"]');
      if (!el) {
        return "gone";
      }
      const template = document.createElement("template");
      template.innerHTML = html;
      if (!keep(template.content)) {
        return "resync";
      }
      const fresh = template.content.firstElementChild;
      if (!fresh) {
        return "applied";
      }
      Idiomorph.morph(el, fresh, config);
      return "applied";
    },

    slot(live: Element, html: string): void {
      Idiomorph.morph(live, html, slotConfig);
    }
  };
}
