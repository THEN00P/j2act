import type { NavMode, Send } from "./protocol.ts";

/*
 * Soft navigation (ADR 0011): links and back/forward go over the socket, and the server answers
 * with patches, then the final URL. Intent on a link (hover, focus, touch) asks the server to
 * mount its target early.
 */

export class Navigation {
  private pendingHash = "";
  private readonly preloadedAt = new Map<string, number>();   // app URL -> when its preload was asked for
  private hoverTimer: ReturnType<typeof setTimeout> | undefined;
  private readonly send: Send;
  private readonly base: string;
  private readonly preloadByDefault: boolean;

  constructor(send: Send, base: string, preloadByDefault: boolean) {
    this.send = send;
    this.base = base;
    this.preloadByDefault = preloadByDefault;
  }

  listen(doc: Document): void {
    if ("scrollRestoration" in history) {
      history.scrollRestoration = "manual";
    }
    history.replaceState({ j2: 1, y: window.scrollY }, "");

    doc.addEventListener("mouseover", (e) => {
      const u = this.preloadUrl(e.target);
      clearTimeout(this.hoverTimer);
      if (u) {
        this.hoverTimer = setTimeout(() => this.preload(u), 50); // a pass-over is not intent
      }
    });
    for (const type of ["focusin", "touchstart"]) {
      doc.addEventListener(type, (e) => {
        const u = this.preloadUrl(e.target);
        if (u) {
          this.preload(u);
        }
      }, { passive: true });
    }

    doc.addEventListener("click", (e) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) {
        return;
      }
      const a = link(e.target);
      if (!a || a.hasAttribute("data-j2-click") || a.hasAttribute("download") || a.hasAttribute("data-j2-reload")
        || (a.target && a.target !== "_self")) {
        return;
      }
      const url = new URL(a.href, location.href);
      if (!this.inApp(url)) {
        return;
      }
      if (url.pathname === location.pathname && url.search === location.search && url.hash) {
        return; // same-page anchor: the browser scrolls
      }
      e.preventDefault();
      this.go(url, "push");
    });

    window.addEventListener("popstate", () => {
      this.pendingHash = location.hash;
      this.send({ t: "nav", u: location.pathname + location.search, m: "pop" });
    });
  }

  /** The server's answer to a navigation, after its patches: the final URL, after redirects. */
  landed(u: string, mode: NavMode): void {
    const target = u + this.pendingHash;
    if (mode === "push") {
      history.pushState({ j2: 1, y: 0 }, "", target);
    } else if (mode === "replace") {
      history.replaceState({ j2: 1, y: 0 }, "", target);
    }
    if (mode === "pop") {
      const y = history.state && typeof history.state.y === "number" ? history.state.y : 0;
      window.scrollTo(0, y);
    } else if (this.pendingHash) {
      const anchor = document.getElementById(decodeURIComponent(this.pendingHash.slice(1)));
      if (anchor) {
        anchor.scrollIntoView();
      }
    } else {
      window.scrollTo(0, 0);
    }
    this.pendingHash = "";
  }

  private go(url: URL, mode: NavMode): void {
    history.replaceState({ j2: 1, y: window.scrollY }, "");
    this.pendingHash = url.hash;
    this.send({ t: "nav", u: url.pathname + url.search, m: mode });
  }

  private inApp(url: URL): boolean {
    return url.origin === location.origin
      && (this.base === "" || url.pathname === this.base || url.pathname.indexOf(this.base + "/") === 0);
  }

  private preloadUrl(target: EventTarget | null): string | null {
    const a = link(target);
    if (!a || a.hasAttribute("download") || a.hasAttribute("data-j2-reload") || (a.target && a.target !== "_self")) {
      return null;
    }
    const mode = a.getAttribute("data-j2-preload");
    if (mode === "none" || (mode !== "intent" && !this.preloadByDefault)) {
      return null;
    }
    const url = new URL(a.href, location.href);
    if (!this.inApp(url) || (url.pathname === location.pathname && url.search === location.search)) {
      return null;
    }
    return url.pathname + url.search;
  }

  private preload(u: string): void {
    const at = this.preloadedAt.get(u);
    if (at && Date.now() - at < 5000) {
      return;
    }
    this.preloadedAt.set(u, Date.now());
    this.send({ t: "pre", u: u });
  }
}

function link(target: EventTarget | null): HTMLAnchorElement | null {
  return target instanceof Element ? target.closest<HTMLAnchorElement>("a[href]") : null;
}
