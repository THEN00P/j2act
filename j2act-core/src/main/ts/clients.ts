import type { ActionCall, Send } from "./protocol.ts";
import type { Uploads } from "./uploads.ts";
import { errorText, named } from "./util.ts";

/*
 * Client modules (ADR 0022): their lifecycle, actions, callbacks and slots. After every patch,
 * new client elements mount, changed props update and removed ones clean up.
 */

/** A module's export for one client, as the processor's .types.d.ts describe it. */
export interface ClientApi {
  mount?(el: Element, props?: unknown): unknown;
  update?(el: Element, props: unknown, previous: unknown): void;
  [action: string]: unknown;
}

interface Client {
  id: string;
  el: Element;
  url: string | null;
  /** The props as rendered, a JSON string compared to notice changes. */
  props: string | null;
  api: ClientApi | null;
  decoded: unknown;
  cleanup: (() => void) | null;
  ready: boolean;
  failure: { error: unknown } | null;
  /** Actions Java called before the module mounted. */
  waiting: ActionCall[];
}

export interface ClientsOptions {
  send: Send;
  uploads: Uploads;
  /** Morphs a slot's live element to the slot's fresh markup. */
  morphSlot: (live: Element, html: string) => void;
}

export class Clients {
  private readonly clients = new Map<string, Client>();       // client id ->
  private readonly liveAnchors = new Set<string>();           // anchors of live components handed to actions
  private readonly lostSlots = new Set<string>();             // slot ids already reported as gone
  private readonly serverAttrs = new WeakMap<Element, string[]>(); // the attributes the last render set
  private readonly stylesheets = new Map<string, Promise<void>>(); // url -> its <link> having loaded
  private readonly send: Send;
  private readonly uploads: Uploads;
  private readonly morphSlot: (live: Element, html: string) => void;

  constructor(options: ClientsOptions) {
    this.send = options.send;
    this.uploads = options.uploads;
    this.morphSlot = options.morphSlot;
  }

  /** Mounts new client elements, updates changed props and unmounts removed ones. */
  scan(): void {
    document.querySelectorAll("[data-j2-client]").forEach((el) => this.mount(el));
    this.clients.forEach((c) => {
      if (!c.el.isConnected || c.el.getAttribute("data-j2-client") !== c.id) {
        this.unmount(c);
      }
    });
  }

  /** Values from Java: callbacks become functions, slots elements, Upload targets objects with send(). */
  revive(json: string): unknown {
    return JSON.parse(json, (_key, value) => {
      if (value && typeof value === "object" && !Array.isArray(value)) {
        if (typeof value.$fn === "string") {
          return this.callback(value.$fn);
        }
        if (typeof value.$slot === "string") {
          return document.querySelector('[data-j2-slot="' + CSS.escape(value.$slot) + '"]');
        }
        if (typeof value.$upload === "string") {
          return this.uploads.target(value.$upload);
        }
        if (typeof value.$html === "string") {
          // A snapshot: rendered once, the client's from now on.
          const snapshot = document.createElement("div");
          snapshot.style.display = "contents";
          snapshot.innerHTML = value.$html;
          return snapshot;
        }
        if (typeof value.$live === "string") {
          // A live component: its root carries its anchor, so later patches find it wherever it goes.
          const template = document.createElement("template");
          template.innerHTML = value.$live;
          const root = template.content.firstElementChild!;
          this.liveAnchors.add(root.getAttribute("data-j2s")!);
          return root;
        }
      }
      return value;
    });
  }

  /**
   * A patch found no element for its anchor. If it was live content given to an action, the
   * client dropped it, and the server stops patching it.
   */
  anchorGone(anchor: string): void {
    if (this.liveAnchors.delete(anchor)) {
      console.warn("j2act: live content " + anchor + " passed to a client action left the document (ADR 0022)");
      this.send({ t: "lg", s: anchor });
    }
  }

  /** A call from Java, sent after the patch: queued until its client has mounted. */
  call(m: ActionCall): void {
    const c = this.clients.get(m.c);
    if (!c) {
      this.reply(m.i, false, named("AbortError", "the client is not mounted"));
    } else if (c.failure) {
      this.reply(m.i, false, c.failure.error);
    } else if (!c.ready) {
      c.waiting.push(m);
    } else {
      this.run(c, m);
    }
  }

  /** An action bound to an event, run inside it: the client must have mounted already. */
  callNow(id: string, name: string, args: unknown[]): unknown {
    const c = this.clients.get(id);
    if (!c || !c.ready) {
      throw c && c.failure ? c.failure.error : named("InvalidStateError", "the client has not mounted yet");
    }
    return this.invoke(c, name, this.revive(JSON.stringify(args)) as unknown[]);
  }

  /** Answers a call from Java, an action's or a window() call's. */
  reply(i: string, ok: boolean, value: unknown): void {
    if (ok) {
      try {
        this.send({ t: "cr", i: i, ok: "1", v: JSON.stringify(value === undefined ? null : value) });
        return;
      } catch (e) {
        value = e;
      }
    }
    console.warn("j2act: client action failed", value);
    this.send({ t: "cr", i: i, ok: "0", e: errorText(value) });
  }

  /** Server attributes follow the render; attributes the client added stay; slots morph where they live. */
  sync(el: Element, fresh: Element): void {
    const names = attributeNames(fresh);
    for (const name of this.serverAttrs.get(el) || []) {
      if (names.indexOf(name) < 0) {
        el.removeAttribute(name);
      }
    }
    for (const name of names) {
      const value = fresh.getAttribute(name)!;
      if (el.getAttribute(name) !== value) {
        el.setAttribute(name, value);
      }
    }
    this.serverAttrs.set(el, names);
    fresh.querySelectorAll(":scope > [data-j2-slot]").forEach((slot) => {
      const id = slot.getAttribute("data-j2-slot")!;
      const live = document.querySelector('[data-j2-slot="' + CSS.escape(id) + '"]');
      if (live) {
        this.morphSlot(live, slot.innerHTML);
      } else if (!this.lostSlots.has(id)) {
        this.lostSlots.add(id);
        console.warn("j2act: slot " + id + " left the document, so it stopped updating (ADR 0022)");
        this.send({ t: "ce", c: el.getAttribute("data-j2-client"), e: "slot " + id + " left the document, so it stopped updating" });
      }
    });
  }

  /**
   * Dev mode (ADR 0024): a new build moved these files. Stylesheets swap in place without a
   * flash, clients whose module moved mount again with their current props; Java state is untouched.
   */
  reimport(moved: Record<string, string>): void {
    if (moved["*"]) {
      location.reload();
      return;
    }
    document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]').forEach((link) => {
      const href = link.getAttribute("href")!;
      const next = moved[href];
      if (!next) {
        return;
      }
      const fresh = link.cloneNode() as HTMLLinkElement;
      fresh.href = next;
      fresh.onload = () => link.remove();
      link.after(fresh);
      const loaded = this.stylesheets.get(href);
      if (loaded) {
        this.stylesheets.set(next, loaded);
        this.stylesheets.delete(href);
      }
    });
    document.querySelectorAll("[data-j2-client]").forEach((el) => {
      const url = el.getAttribute("data-j2-module");
      if (url && moved[url]) {
        el.setAttribute("data-j2-module", moved[url]);
      }
      const css = el.getAttribute("data-j2-css");
      if (css) {
        el.setAttribute("data-j2-css", css.split(" ").map((u) => moved[u] || u).join(" "));
      }
    });
    this.scan();
  }

  private mount(el: Element): void {
    const id = el.getAttribute("data-j2-client")!;
    const url = el.getAttribute("data-j2-module");
    let c = this.clients.get(id);
    // A new build of the module (dev mode) mounts again; otherwise only props can change.
    if (c && c.el === el && c.url === url) {
      this.propsChanged(c);
      return;
    }
    if (c) {
      this.unmount(c);
    }
    const client: Client = { id: id, el: el, url: url, props: el.getAttribute("data-j2-props"), api: null,
      decoded: null, cleanup: null, ready: false, failure: null, waiting: [] };
    c = client;
    this.clients.set(id, client);
    if (!this.serverAttrs.has(el)) {
      this.serverAttrs.set(el, attributeNames(el));
    }
    const name = el.getAttribute("data-j2-export")!;
    const styles = (el.getAttribute("data-j2-css") || "").split(" ").filter(Boolean);
    // A build's stylesheets (CSS imports, CSS modules) are in place before mount runs.
    Promise.all([import(/* @vite-ignore */ url!), ...styles.map((s) => this.stylesheet(s))]).then(([module]) => {
      if (this.clients.get(id) !== client) {
        return;
      }
      if (!module[name] || typeof module[name] !== "object") {
        throw named("ReferenceError", url + " has no export " + name);
      }
      client.api = module[name];
      this.start(client);
    }).catch((e) => {
      client.failure = { error: e };
      this.error(client, "import", e);
      this.failWaiting(client, e);
    });
  }

  private stylesheet(url: string): Promise<void> {
    let loaded = this.stylesheets.get(url);
    if (!loaded) {
      loaded = new Promise((resolve, reject) => {
        const link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = url;
        link.setAttribute("data-j2-keep", "");
        link.onload = () => resolve();
        link.onerror = () => reject(named("NetworkError", "stylesheet " + url + " did not load"));
        document.head.append(link);
      });
      this.stylesheets.set(url, loaded);
    }
    return loaded;
  }

  private start(c: Client): void {
    const api = c.api!;
    try {
      c.decoded = c.props == null ? null : this.revive(c.props);
      if (typeof api.mount === "function") {
        const cleanup = c.props == null ? api.mount(c.el) : api.mount(c.el, c.decoded);
        c.cleanup = typeof cleanup === "function" ? cleanup as () => void : null;
      }
    } catch (e) {
      c.failure = { error: e };
      this.error(c, "mount", e);
      this.failWaiting(c, e);
      return;
    }
    c.ready = true;
    const waiting = c.waiting;
    c.waiting = [];
    waiting.forEach((m) => this.run(c, m));
  }

  /** update runs only when props really changed; without it the client mounts again. */
  private propsChanged(c: Client): void {
    const props = c.el.getAttribute("data-j2-props");
    if (props === c.props) {
      return;
    }
    c.props = props;
    if (!c.ready) {
      return; // mount reads the latest props when its import lands
    }
    const api = c.api!;
    if (typeof api.update === "function") {
      const previous = c.decoded;
      try {
        c.decoded = this.revive(props!);
        api.update(c.el, c.decoded, previous);
      } catch (e) {
        this.error(c, "update", e);
      }
    } else {
      this.stop(c);
      c.ready = false;
      this.start(c);
    }
  }

  private stop(c: Client): void {
    if (c.cleanup) {
      try {
        c.cleanup();
      } catch (e) {
        this.error(c, "cleanup", e);
      }
      c.cleanup = null;
    }
  }

  private unmount(c: Client): void {
    this.clients.delete(c.id);
    this.stop(c);
    this.failWaiting(c, named("AbortError", "the client unmounted"));
  }

  private failWaiting(c: Client, e: unknown): void {
    const waiting = c.waiting;
    c.waiting = [];
    waiting.forEach((m) => this.reply(m.i, false, e));
  }

  private invoke(c: Client, name: string, args: unknown[]): unknown {
    const fn = c.api![name];
    if (typeof fn !== "function") {
      throw named("TypeError", c.el.getAttribute("data-j2-export") + " has no action " + name);
    }
    return fn.apply(c.api, [c.el, ...args]);
  }

  private run(c: Client, m: ActionCall): void {
    let result;
    try {
      result = this.invoke(c, m.n, this.revive(m.a) as unknown[]);
    } catch (e) {
      this.reply(m.i, false, e);
      return;
    }
    Promise.resolve(result).then((v) => this.reply(m.i, true, v), (e) => this.reply(m.i, false, e));
  }

  private callback(id: string): (...args: unknown[]) => void {
    return (...args) => {
      this.send({ t: "cb", h: id, v: JSON.stringify(args) });
    };
  }

  /** Mount, update and cleanup failures and missing exports: the console here, the log on the server. */
  private error(c: Client, what: string, e: unknown): void {
    console.error("j2act: client " + c.el.getAttribute("data-j2-export") + " failed in " + what, e);
    this.send({ t: "ce", c: c.id, e: what + ": " + errorText(e) });
  }
}

function attributeNames(el: Element): string[] {
  return Array.from(el.attributes, (a) => a.name);
}
