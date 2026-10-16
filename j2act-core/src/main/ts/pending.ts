export type Pending = ReturnType<typeof createPending>;

/**
 * Pending UI (ADR 0013): instant on click or submit, reverted on the ack unless a morph already
 * replaced it. The element is blocked meanwhile; swap is where withPending markup goes, which for
 * a submit is the submitter button.
 */
export function createPending() {
  const inFlight = new Map<string, { el: Element; swap: Element }>();   // ack id ->
  const blocked = new Set<Element>();
  const saved = new WeakMap<Element, string>();                          // swap -> its markup before

  return {
    has(el: Element): boolean {
      return blocked.has(el);
    },

    get size(): number {
      return inFlight.size;
    },

    start(el: Element, ack: string, swap: Element): void {
      blocked.add(el);
      inFlight.set(ack, { el: el, swap: swap });
      for (const target of [el, swap]) {
        target.setAttribute("data-pending", "");
        target.setAttribute("aria-busy", "true");
      }
      const tpl = swap.querySelector<HTMLTemplateElement>(":scope > template[data-j2-pending]");
      if (tpl) {
        saved.set(swap, swap.innerHTML);
        swap.innerHTML = tpl.outerHTML + "<span data-j2-pending-live>" + tpl.innerHTML + "</span>";
      }
    },

    ack(ack: string): void {
      const entry = inFlight.get(ack);
      inFlight.delete(ack);
      if (!entry) {
        return;
      }
      blocked.delete(entry.el);
      for (const target of [entry.el, entry.swap]) {
        target.removeAttribute("data-pending");
        target.removeAttribute("aria-busy");
      }
      const swap = entry.swap;
      const before = saved.get(swap);
      saved.delete(swap);
      if (before !== undefined && swap.querySelector(":scope > [data-j2-pending-live]")) {
        swap.innerHTML = before;
      }
    },

    /** A new server-side tab will never ack these. */
    clear(): void {
      inFlight.clear();
      blocked.clear();
    }
  };
}
