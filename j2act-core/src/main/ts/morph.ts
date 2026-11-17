import { Idiomorph } from "idiomorph";
import type { Config } from "idiomorph";
import { isTextField, type Fields } from "./fields.ts";
import type { Pending } from "./pending.ts";

export type Morpher = ReturnType<typeof createMorpher>;

/**
 * Applies the server's patches with Idiomorph (ADR 0003), keeping what is the browser's: the
 * pending state of a blocked element, form state the render did not set (ADR 0013), a client
 * element's node and children (ADR 0022), and what the runtime added to the head.
 */
export function createMorpher(pending: Pending, syncClient: (el: Element, fresh: Element) => void, fields: Fields) {
  /** The last event the server had handled, from the patch being applied. */
  let handled = 0;
  /** Copies of what the page already has, standing in for a patch's stubs: the morph leaves their originals alone. */
  const kept = new WeakSet<Node>();

  /**
   * Puts in a copy of the live element for each stub in a patch, a scope the server knows the page has as it renders
   * now (ADR 0036). False when one is not on the page, which has then drifted from the server.
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
      const live = document.querySelector('[data-j2-scope="' + stub.data.slice(3) + '"]');
      if (!live) {
        return false;
      }
      const copy = live.cloneNode(true);
      kept.add(copy);
      stub.replaceWith(copy);
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
      if (kept.has(newNode) && oldNode.nodeType === 1
        && oldNode.getAttribute("data-j2-scope") === (newNode as Element).getAttribute("data-j2-scope")) {
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
        return false;
      }
      if (!stubs) {
        Idiomorph.morph(el, html, config);
        return true;
      }
      const parsed = document.createElement("template");
      parsed.innerHTML = html;
      if (!expandStubs(parsed.content)) {
        return false;
      }
      Idiomorph.morph(el, parsed.content.firstElementChild!, config);
      return true;
    },

    slot(live: Element, html: string): void {
      Idiomorph.morph(live, html, slotConfig);
    }
  };
}
