/**
 * Pending UI (ADR 0013): instant on click or submit, reverted on the ack unless a morph already
 * replaced it. The element is blocked meanwhile; swap is where withPending markup goes, which for
 * a submit is the submitter button.
 */
export class Pending {
  private readonly inFlight = new Map<string, { el: Element; swap: Element }>();   // ack id ->
  private readonly blocked = new Set<Element>();
  private readonly saved = new WeakMap<Element, string>();                          // swap -> its markup before

  has(el: Element): boolean {
    return this.blocked.has(el);
  }

  get size(): number {
    return this.inFlight.size;
  }

  start(el: Element, ack: string, swap: Element): void {
    this.blocked.add(el);
    this.inFlight.set(ack, { el: el, swap: swap });
    for (const target of [el, swap]) {
      target.setAttribute("data-pending", "");
      target.setAttribute("aria-busy", "true");
    }
    const tpl = swap.querySelector<HTMLTemplateElement>(":scope > template[data-j2-pending]");
    if (tpl) {
      this.saved.set(swap, swap.innerHTML);
      swap.innerHTML = tpl.outerHTML + "<span data-j2-pending-live>" + tpl.innerHTML + "</span>";
    }
  }

  ack(ack: string): void {
    const entry = this.inFlight.get(ack);
    this.inFlight.delete(ack);
    if (!entry) {
      return;
    }
    this.blocked.delete(entry.el);
    for (const target of [entry.el, entry.swap]) {
      target.removeAttribute("data-pending");
      target.removeAttribute("aria-busy");
    }
    const swap = entry.swap;
    const saved = this.saved.get(swap);
    this.saved.delete(swap);
    if (saved !== undefined && swap.querySelector(":scope > [data-j2-pending-live]")) {
      swap.innerHTML = saved;
    }
  }

  /** A new session will never ack these. */
  clear(): void {
    this.inFlight.clear();
    this.blocked.clear();
  }
}
