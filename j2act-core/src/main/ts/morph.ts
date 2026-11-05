import { Idiomorph } from "idiomorph";
import type { Config } from "idiomorph";
import type { Pending } from "./pending.ts";

export type Morpher = ReturnType<typeof createMorpher>;

/**
 * Applies the server's patches with Idiomorph (ADR 0003), keeping what is the browser's: the
 * pending state of a blocked element, form state the render did not set (ADR 0013), a client
 * element's node and children (ADR 0022), and what the runtime added to the head.
 */
export function createMorpher(pending: Pending, syncClient: (el: Element, fresh: Element) => void) {
  const callbacks: Config["callbacks"] = {
    beforeAttributeUpdated: (name, el) => {
      if (pending.has(el) && (name === "data-pending" || name === "aria-busy")) {
        return false;
      }
      // What is being typed is the user's, whatever this render says.
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
      if (oldNode.nodeType === 1 && newNode.nodeType === 1 && oldNode.hasAttribute("data-j2-client")
        && oldNode.getAttribute("data-j2-client") === (newNode as Element).getAttribute("data-j2-client")) {
        syncClient(oldNode, newNode as Element);
        return false;
      }
      // Being typed in, its contents are the user's. Idiomorph's ignoreActiveValue would do this for any focused
      // element, and a click focuses its button, so a button's label would stop updating.
      if (oldNode === document.activeElement && oldNode.nodeType === 1 && newNode.nodeType === 1
        && (oldNode.tagName === "TEXTAREA" || oldNode.hasAttribute("contenteditable"))) {
        (newNode as Element).innerHTML = oldNode.innerHTML;
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
    patch(anchor: string, html: string, isRoot: boolean): boolean {
      if (isRoot) {
        const doc = new DOMParser().parseFromString(html, "text/html");
        document.documentElement.setAttribute("data-j2-scope", anchor);
        head(doc.head.innerHTML);
        body(doc.body);
        return true;
      }
      const el = document.querySelector('[data-j2-scope="' + anchor + '"]');
      if (!el) {
        return false;
      }
      Idiomorph.morph(el, html, config);
      return true;
    },

    slot(live: Element, html: string): void {
      Idiomorph.morph(live, html, slotConfig);
    }
  };
}
