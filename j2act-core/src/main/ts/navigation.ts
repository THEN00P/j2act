import type { NavMode, Send } from "./protocol.ts";

export type Navigation = ReturnType<typeof createNavigation>;

/**
 * Soft navigation (ADR 0011): links and back/forward go over the socket, and the server answers
 * with patches, then the final URL. Intent on a link (hover, focus, touch) asks the server to
 * mount its target early.
 */
export function createNavigation(send: Send, base: string, preloadByDefault: boolean) {
  let pendingHash = "";
  const preloadedAt = new Map<string, number>();   // app URL -> when its preload was asked for
  let hoverTimer: ReturnType<typeof setTimeout> | undefined;

  function go(url: URL, mode: NavMode): void {
    history.replaceState({ j2: 1, y: window.scrollY }, "");
    pendingHash = url.hash;
    send({ t: "nav", u: url.pathname + url.search, m: mode });
  }

  function inApp(url: URL): boolean {
    return url.origin === location.origin
      && (base === "" || url.pathname === base || url.pathname.indexOf(base + "/") === 0);
  }

  function preloadUrl(target: EventTarget | null): string | null {
    const a = link(target);
    if (!a || a.hasAttribute("download") || a.hasAttribute("data-j2-reload") || (a.target && a.target !== "_self")) {
      return null;
    }
    const mode = a.getAttribute("data-j2-preload");
    if (mode === "none" || (mode !== "intent" && !preloadByDefault)) {
      return null;
    }
    const url = new URL(a.href, location.href);
    if (!inApp(url) || (url.pathname === location.pathname && url.search === location.search)) {
      return null;
    }
    return url.pathname + url.search;
  }

  function preload(u: string): void {
    const at = preloadedAt.get(u);
    if (at && Date.now() - at < 5000) {
      return;
    }
    preloadedAt.set(u, Date.now());
    send({ t: "pre", u: u });
  }

  return {
    listen(doc: Document): void {
      if ("scrollRestoration" in history) {
        history.scrollRestoration = "manual";
      }
      history.replaceState({ j2: 1, y: window.scrollY }, "");

      doc.addEventListener("mouseover", (e) => {
        const u = preloadUrl(e.target);
        clearTimeout(hoverTimer);
        if (u) {
          hoverTimer = setTimeout(() => preload(u), 50); // a pass-over is not intent
        }
      });
      for (const type of ["focusin", "touchstart"]) {
        doc.addEventListener(type, (e) => {
          const u = preloadUrl(e.target);
          if (u) {
            preload(u);
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
        if (!inApp(url)) {
          return;
        }
        if (url.pathname === location.pathname && url.search === location.search && url.hash) {
          return; // same-page anchor: the browser scrolls
        }
        e.preventDefault();
        go(url, "push");
      });

      window.addEventListener("popstate", () => {
        pendingHash = location.hash;
        send({ t: "nav", u: location.pathname + location.search, m: "pop" });
      });
    },

    /** The server's answer to a navigation, after its patches: the final URL, after redirects. */
    landed(u: string, mode: NavMode): void {
      const target = u + pendingHash;
      if (mode === "push") {
        history.pushState({ j2: 1, y: 0 }, "", target);
      } else if (mode === "replace") {
        history.replaceState({ j2: 1, y: 0 }, "", target);
      }
      if (mode === "pop") {
        const y = history.state && typeof history.state.y === "number" ? history.state.y : 0;
        window.scrollTo(0, y);
      } else if (pendingHash) {
        const anchor = document.getElementById(decodeURIComponent(pendingHash.slice(1)));
        if (anchor) {
          anchor.scrollIntoView();
        }
      } else {
        window.scrollTo(0, 0);
      }
      pendingHash = "";
    }
  };
}

function link(target: EventTarget | null): HTMLAnchorElement | null {
  return target instanceof Element ? target.closest<HTMLAnchorElement>("a[href]") : null;
}
