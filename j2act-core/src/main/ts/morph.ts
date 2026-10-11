import { Idiomorph } from "idiomorph";
import type { Config } from "idiomorph";
import type { Pending } from "./pending.ts";

/**
 * Applies the server's patches with Idiomorph (ADR 0003), keeping what is the browser's: the
 * pending state of a blocked element, form state the render did not set (ADR 0013), a client
 * element's node and children (ADR 0022), and what the runtime added to the head.
 */
export class Morpher {
  private readonly config: Config;
  private readonly slotConfig: Config;
  private readonly headConfig: Config;

  constructor(pending: Pending, syncClient: (el: Element, fresh: Element) => void) {
    const callbacks: Config["callbacks"] = {
      beforeAttributeUpdated: (name, el) => {
        if (pending.has(el) && (name === "data-pending" || name === "aria-busy")) {
          return false;
        }
        // Form state the render did not set is the user's: keep it (ADR 0013).
        if ((name === "value" || name === "checked" || name === "selected") && el.hasAttribute("data-j2-ctl")) {
          return el.getAttribute("data-j2-ctl")!.split(" ").indexOf(name) >= 0;
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
        return true;
      }
    };
    this.config = { morphStyle: "outerHTML", ignoreActiveValue: true, callbacks: callbacks };
    this.slotConfig = { morphStyle: "innerHTML", ignoreActiveValue: true, callbacks: callbacks };
    // Head morphs leave alone what the runtime added there: client stylesheets.
    this.headConfig = {
      morphStyle: "innerHTML",
      callbacks: { beforeNodeRemoved: (node) => !(node.nodeType === 1 && node.hasAttribute("data-j2-keep")) }
    };
  }

  /**
   * Morphs the component at anchor; false when it is not in the document. The session root may
   * be a new component after a navigation, with a new anchor, so it replaces head and body.
   */
  patch(anchor: string, html: string, isRoot: boolean): boolean {
    if (isRoot) {
      const doc = new DOMParser().parseFromString(html, "text/html");
      document.documentElement.setAttribute("data-j2s", anchor);
      this.head(doc.head.innerHTML);
      this.body(doc.body);
      return true;
    }
    const el = document.querySelector('[data-j2s="' + anchor + '"]');
    if (!el) {
      return false;
    }
    Idiomorph.morph(el, html, this.config);
    return true;
  }

  head(html: string): void {
    Idiomorph.morph(document.head, html, this.headConfig);
  }

  body(body: HTMLElement): void {
    Idiomorph.morph(document.body, body, this.config);
  }

  slot(live: Element, html: string): void {
    Idiomorph.morph(live, html, this.slotConfig);
  }
}
