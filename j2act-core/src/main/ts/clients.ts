import type { ClientMessage } from "./protocol.ts";
import type { ClientCall, Send } from "./protocol.ts";
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
  waiting: ClientCall[];
}

export interface ClientsOptions {
  send: Send;
  /** Whether a message sent now goes into a socket rather than a queue. */
  connected: () => boolean;
  uploads: Uploads;
  /** Morphs a slot's live element to the slot's fresh markup. */
  morphSlot: (live: Element, html: string) => void;
}

export type Clients = ReturnType<typeof createClients>;

/** At most this many results a second, well under the server's default limit of 30 a second. */
const RESULTS_PER_SECOND = 20;
/** A result with no answer after this long goes again: the server's rate limit refuses without one. */
const RESULT_RETRY_MS = 2000;

export function createClients({ send, connected, uploads, morphSlot }: ClientsOptions) {
  const clients = new Map<string, Client>();       // client id ->
  const liveAnchors = new Set<string>();           // anchors of live components handed to actions
  const lostSlots = new Set<string>();             // slot ids already reported as gone
  const serverAttrs = new WeakMap<Element, string[]>(); // the attributes the last render set
  const stylesheets = new Map<string, Promise<void>>(); // url -> its <link> having loaded

  /** Mounts new client elements, updates changed props and unmounts removed ones. */
  function scan(): void {
    document.querySelectorAll("[data-j2-client]").forEach((el) => mount(el));
    clients.forEach((c) => {
      if (!c.el.isConnected || c.el.getAttribute("data-j2-client") !== c.id) {
        unmount(c);
      }
    });
  }

  /** Values from Java: callbacks become functions, slots elements, Upload targets objects with send(). */
  function revive(json: string): unknown {
    return JSON.parse(json, (_key, value) => {
      if (value && typeof value === "object" && !Array.isArray(value)) {
        if (typeof value.$fn === "string") {
          return callback(value.$fn);
        }
        if (typeof value.$slot === "string") {
          return document.querySelector('[data-j2-slot="' + CSS.escape(value.$slot) + '"]');
        }
        if (typeof value.$upload === "string") {
          return uploads.target(value.$upload);
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
          liveAnchors.add(root.getAttribute("data-j2-scope")!);
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
  function anchorGone(anchor: string): boolean {
    if (liveAnchors.delete(anchor)) {
      console.warn("j2act: live content " + anchor + " passed to a client action left the document (ADR 0022)");
      send({ type: "liveGone", anchor: anchor });
      return true;
    }
    return false;
  }

  /** A call from Java, sent after the patch: queued until its client has mounted. */
  function call(m: ClientCall): void {
    const c = clients.get(m.client);
    if (!c) {
      reply(m.id, false, named("AbortError", "the client is not mounted"));
    } else if (c.failure) {
      reply(m.id, false, c.failure.error);
    } else if (!c.ready) {
      c.waiting.push(m);
    } else {
      run(c, m);
    }
  }

  /** An action bound to an event, run inside it: the client must have mounted already. */
  function callNow(id: string, name: string, args: unknown[]): unknown {
    const c = clients.get(id);
    if (!c || !c.ready) {
      throw c && c.failure ? c.failure.error : named("InvalidStateError", "the client has not mounted yet");
    }
    return invoke(c, name, revive(JSON.stringify(args)) as unknown[]);
  }

  /** Results the server has not acknowledged: a socket can die with one on its way (ADR 0032). */
  const unseenResults = new Map<string, { message: ClientMessage; sentAt: number }>();
  /** Which server-side tab this is, of those the page has had: call ids start over with each. */
  let tabGeneration = 0;
  let maxFrame = Number.POSITIVE_INFINITY;
  let resultAllowance = RESULTS_PER_SECOND;
  let resultTimer: ReturnType<typeof setTimeout> | null = null;

  function sendResult(id: string, result: ClientMessage): void {
    unseenResults.set(id, { message: result, sentAt: 0 });
    sendResults();
  }

  /** The oldest that are due, as many as this second allows; a timer sees to the rest while any has no answer. */
  function sendResults(): void {
    if (!connected()) {
      return; // "ready" sends them
    }
    const now = Date.now();
    for (const [id, result] of unseenResults) {
      if (resultAllowance === 0) {
        break;
      }
      if (now - result.sentAt >= RESULT_RETRY_MS) {
        // The server closes a socket over a frame past its limit, so this one would come back with every socket.
        const size = JSON.stringify(result.message).length;
        if (size > maxFrame) {
          result.message = { type: "callResult", id: id, ok: false,
            error: "RangeError: the result is " + size + " characters, over the socket's limit of " + maxFrame };
        }
        result.sentAt = now;
        resultAllowance--;
        send(result.message);
      }
    }
    if (unseenResults.size > 0 && resultTimer === null) {
      resultTimer = setTimeout(() => {
        resultTimer = null;
        resultAllowance = RESULTS_PER_SECOND;
        sendResults();
      }, 1000);
    }
  }

  /** The server has this result. */
  function resultSeen(id: string): void {
    unseenResults.delete(id);
  }

  /** The socket is back: what the old one may have lost is due again, and the server ignores what it settled. */
  function resendResults(limit: number = maxFrame): void {
    maxFrame = limit;
    unseenResults.forEach((result) => {
      result.sentAt = 0;
    });
    sendResults();
  }

  /** A new server-side tab: it never made those calls. */
  function forgetResults(): void {
    unseenResults.clear();
    tabGeneration++;
    // Calls still waiting for their module belong to the old tab too: unmounting would answer them.
    clients.forEach((c) => {
      c.waiting = [];
    });
  }

  /** What a call running now answers with, later: if a new tab has come by then, its answer means nothing. */
  function replier(id: string): (ok: boolean, value: unknown) => void {
    const generation = tabGeneration;
    return (ok, value) => {
      if (generation === tabGeneration) {
        reply(id, ok, value);
      }
    };
  }

  /** Answers a call from Java, an action's or a window() call's. */
  function reply(id: string, ok: boolean, value: unknown): void {
    if (ok) {
      try {
        sendResult(id, { type: "callResult", id: id, ok: true, value: JSON.stringify(value === undefined ? null : value) });
        return;
      } catch (e) {
        value = e;
      }
    }
    console.warn("j2act: client action failed", value);
    sendResult(id, { type: "callResult", id: id, ok: false, error: errorText(value) });
  }

  /** Server attributes follow the render; attributes the client added stay; slots morph where they live. */
  function sync(el: Element, fresh: Element): void {
    const names = attributeNames(fresh);
    for (const name of serverAttrs.get(el) || []) {
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
    serverAttrs.set(el, names);
    fresh.querySelectorAll(":scope > [data-j2-slot]").forEach((slot) => {
      const id = slot.getAttribute("data-j2-slot")!;
      const live = document.querySelector('[data-j2-slot="' + CSS.escape(id) + '"]');
      if (live) {
        morphSlot(live, slot.innerHTML);
      } else if (!lostSlots.has(id)) {
        lostSlots.add(id);
        console.warn("j2act: slot " + id + " left the document, so it stopped updating (ADR 0022)");
        send({ type: "clientError", client: el.getAttribute("data-j2-client"),
          error: "slot " + id + " left the document, so it stopped updating" });
      }
    });
  }

  /**
   * Dev mode (ADR 0024): a new build moved these files. Stylesheets swap in place without a
   * flash, clients whose module moved mount again with their current props; Java state is untouched.
   */
  function reimport(moved: Record<string, string>): void {
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
      const loaded = stylesheets.get(href);
      if (loaded) {
        stylesheets.set(next, loaded);
        stylesheets.delete(href);
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
    scan();
  }

  function mount(el: Element): void {
    const id = el.getAttribute("data-j2-client")!;
    const url = el.getAttribute("data-j2-module");
    let c = clients.get(id);
    // A new build of the module (dev mode) mounts again; otherwise only props can change.
    if (c && c.el === el && c.url === url) {
      propsChanged(c);
      return;
    }
    if (c) {
      unmount(c);
    }
    const client: Client = { id: id, el: el, url: url, props: el.getAttribute("data-j2-props"), api: null,
      decoded: null, cleanup: null, ready: false, failure: null, waiting: [] };
    c = client;
    clients.set(id, client);
    if (!serverAttrs.has(el)) {
      serverAttrs.set(el, attributeNames(el));
    }
    const name = el.getAttribute("data-j2-export")!;
    const styles = (el.getAttribute("data-j2-css") || "").split(" ").filter(Boolean);
    // A build's stylesheets (CSS imports, CSS modules) are in place before mount runs.
    Promise.all([import(/* @vite-ignore */ url!), ...styles.map((s) => stylesheet(s))]).then(([module]) => {
      if (clients.get(id) !== client) {
        return;
      }
      if (!module[name] || typeof module[name] !== "object") {
        throw named("ReferenceError", url + " has no export " + name);
      }
      client.api = module[name];
      start(client);
    }).catch((e) => {
      client.failure = { error: e };
      error(client, "import", e);
      failWaiting(client, e);
    });
  }

  function stylesheet(url: string): Promise<void> {
    let loaded = stylesheets.get(url);
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
      stylesheets.set(url, loaded);
    }
    return loaded;
  }

  function start(c: Client): void {
    const api = c.api!;
    try {
      c.decoded = c.props == null ? null : revive(c.props);
      if (typeof api.mount === "function") {
        const cleanup = c.props == null ? api.mount(c.el) : api.mount(c.el, c.decoded);
        c.cleanup = typeof cleanup === "function" ? cleanup as () => void : null;
      }
    } catch (e) {
      c.failure = { error: e };
      error(c, "mount", e);
      failWaiting(c, e);
      return;
    }
    c.ready = true;
    const waiting = c.waiting;
    c.waiting = [];
    waiting.forEach((m) => run(c, m));
  }

  /** update runs only when props really changed; without it the client mounts again. */
  function propsChanged(c: Client): void {
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
        c.decoded = revive(props!);
        api.update(c.el, c.decoded, previous);
      } catch (e) {
        error(c, "update", e);
      }
    } else {
      stop(c);
      c.ready = false;
      start(c);
    }
  }

  function stop(c: Client): void {
    if (c.cleanup) {
      try {
        c.cleanup();
      } catch (e) {
        error(c, "cleanup", e);
      }
      c.cleanup = null;
    }
  }

  function unmount(c: Client): void {
    clients.delete(c.id);
    stop(c);
    failWaiting(c, named("AbortError", "the client unmounted"));
  }

  function failWaiting(c: Client, e: unknown): void {
    const waiting = c.waiting;
    c.waiting = [];
    waiting.forEach((m) => reply(m.id, false, e));
  }

  function invoke(c: Client, name: string, args: unknown[]): unknown {
    const fn = c.api![name];
    if (typeof fn !== "function") {
      throw named("TypeError", c.el.getAttribute("data-j2-export") + " has no action " + name);
    }
    return fn.apply(c.api, [c.el, ...args]);
  }

  function run(c: Client, m: ClientCall): void {
    let result;
    try {
      result = invoke(c, m.action, revive(m.args) as unknown[]);
    } catch (e) {
      reply(m.id, false, e);
      return;
    }
    const answer = replier(m.id);
    Promise.resolve(result).then((v) => answer(true, v), (e) => answer(false, e));
  }

  function callback(id: string): (...args: unknown[]) => void {
    return (...args) => {
      send({ type: "callback", handler: id, args: JSON.stringify(args) });
    };
  }

  /** Mount, update and cleanup failures and missing exports: the console here, the log on the server. */
  function error(c: Client, what: string, e: unknown): void {
    console.error("j2act: client " + c.el.getAttribute("data-j2-export") + " failed in " + what, e);
    send({ type: "clientError", client: c.id, error: what + ": " + errorText(e) });
  }

  return { scan, revive, anchorGone, call, callNow, reply, replier, resultSeen, resendResults, forgetResults, sync, reimport };
}

function attributeNames(el: Element): string[] {
  return Array.from(el.attributes, (a) => a.name);
}
