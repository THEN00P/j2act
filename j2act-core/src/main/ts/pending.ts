export type Pending = ReturnType<typeof createPending>;

/**
 * Pending UI (ADR 0013): instant on click or submit, reverted on the last ack unless a morph
 * already replaced it. A submit blocks its form meanwhile, and clicks queue; swap is where
 * withPending markup goes, which for a submit is the submitter button.
 */
export function createPending() {
  const inFlight = new Map<string, { el: Element; swap: Element }>();   // ack id ->
  const blocked = new Set<Element>();
  const counts = new Map<Element, number>();                             // element -> events in flight
  const saved = new WeakMap<Element, string>();                          // swap -> its markup before

  return {
    /** An event of this element is in flight, so its pending state is the browser's. */
    has(el: Element): boolean {
      return counts.has(el);
    },

    /** It takes nothing more until its event is answered. */
    blocks(el: Element): boolean {
      return blocked.has(el);
    },

    get size(): number {
      return inFlight.size;
    },

    start(el: Element, ack: string, swap: Element, blocking = false): void {
      if (blocking) {
        blocked.add(el);
      }
      inFlight.set(ack, { el: el, swap: swap });
      for (const target of new Set([el, swap])) {
        counts.set(target, (counts.get(target) ?? 0) + 1);
        target.setAttribute("data-pending", "");
        target.setAttribute("aria-busy", "true");
      }
      const tpl = swap.querySelector<HTMLTemplateElement>(":scope > template[data-j2-pending]");
      if (tpl && !swap.querySelector(":scope > [data-j2-pending-live]")) {
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
      for (const target of new Set([entry.el, entry.swap])) {
        const left = (counts.get(target) ?? 1) - 1;
        if (left > 0) {
          counts.set(target, left);
        } else {
          counts.delete(target);
          target.removeAttribute("data-pending");
          target.removeAttribute("aria-busy");
        }
      }
      const swap = entry.swap;
      if (counts.has(swap)) {
        return; // Another event of it is still on its way.
      }
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
      counts.clear();
    }
  };
}
