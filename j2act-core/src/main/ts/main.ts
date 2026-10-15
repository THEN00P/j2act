import { createClients } from "./clients.ts";
import { createConnection } from "./connection.ts";
import { listenForEvents } from "./events.ts";
import { createMorpher } from "./morph.ts";
import { createNavigation } from "./navigation.ts";
import { createPending } from "./pending.ts";
import type { ClientMessage, ServerMessage } from "./protocol.ts";
import { navigationType, remember, takePrevious } from "./reopen.ts";
import { createRetained } from "./retained.ts";
import { createUploads } from "./uploads.ts";
import { meta } from "./util.ts";
import { runWeb } from "./web.ts";

/*
 * j2act client runtime. Fixed and small: it forwards events, applies morph patches with
 * Idiomorph, and owns the purely client-side concerns the server cannot do in time: debounce,
 * pending UI and reconnect (ADR 0003, 0010, 0013). It also runs client modules: their
 * lifecycle, actions, callbacks and slots (ADR 0022).
 */

/** One handler per server message type; a type left out does not compile. */
type Handlers = { [T in ServerMessage["type"]]: (m: Extract<ServerMessage, { type: T }>) => void };

function start(): void {
  const sid = meta(document, "j2-tab");
  const wsPath = meta(document, "j2-ws");
  const base = meta(document, "j2-base") || "";
  if (!sid || !wsPath) {
    return;
  }

  let leaving = false;
  let remounting = false;
  const here = () => location.pathname + location.search;
  const previous = takePrevious(here(), meta(document, "j2-token"), navigationType());

  const connection = createConnection((location.protocol === "https:" ? "wss://" : "ws://") + location.host + wsPath,
    sid, meta(document, "j2-token"), onMessage,
    () => !leaving && !remounting && retained.phase !== "paused");
  const send = (message: ClientMessage) => connection.send(message);
  const pending = createPending();
  const uploads = createUploads(send, () => connection.token);
  const morpher = createMorpher(pending, (el, fresh) => clients.sync(el, fresh));
  const clients = createClients({ send: send, uploads: uploads, morphSlot: (live, html) => morpher.slot(live, html) });
  const navigation = createNavigation(send, base, meta(document, "j2-preload") === "intent");
  const retained = createRetained({
    sendIfReady: (message) => connection.sendIfReady(message),
    stopSending: () => connection.hold(),
    remount: remount,
    remounting: () => remounting,
    busy: busy,
    autoPauseAfter: Number(meta(document, "j2-autopause") || 0)
  });

  const handlers: Handlers = {
    ready: () => {
      connection.opened();
      retained.connected();
    },
    patch: (m) => {
      if (!morpher.patch(m.anchor, m.html, m.root === true)) {
        clients.anchorGone(m.anchor);
      }
      clients.scan();
    },
    head: (m) => morpher.head(m.html),
    url: (m) => navigation.landed(m.url, m.mode),
    load: (m) => location.assign(m.url),
    download: (m) => download(m.url),
    upload: (m) => uploads.upload(m.file, m.url),
    uploadRefused: (m) => uploads.refused(m.file, m.error),
    // A window() call from Java, after the patch it came with.
    windowCall: (m) => {
      let result;
      try {
        result = runWeb(JSON.parse(m.call));
      } catch (e) {
        clients.reply(m.id, false, e);
        return;
      }
      result.then((v) => clients.reply(m.id, true, v), (e) => clients.reply(m.id, false, e));
    },
    clientCall: (m) => clients.call(m),
    ack: (m) => pending.ack(m.id),
    expired: () => {
      if (retained.expired()) {
        remount();
      }
    },
    paused: (m) => retained.paused(m.saved === true),
    pauseRefused: () => retained.refused(),
    pauseRequested: () => retained.requested(),
    modules: (m) => clients.reimport(m.modules),
    // First use of browser state over the socket: a socket cannot set the HttpOnly cookie, a fetch can (ADR 0030).
    browserCookie: (m) => claimBrowserCookie(m.url)
  };

  function onMessage(m: ServerMessage): void {
    (handlers[m.type] as ((m: ServerMessage) => void) | undefined)?.(m);
  }

  // ---- tab lifetime (ADR 0010)

  /** Starts over on a new server-side tab; restoring names a closed page's token instead of this one's (ADR 0030). */
  function remount(restoring?: string): void {
    if (remounting) {
      return;
    }
    remounting = true;
    connection.close();
    // The old page's token lets the server restore its Retained State, if it kept a snapshot (ADR 0026).
    const tok = restoring ?? connection.token;
    fetch(location.href, { credentials: "same-origin", headers: tok ? { "X-J2-Restore": tok } : {} })
      .then((r) => r.text())
      .then((html) => {
        const doc = new DOMParser().parseFromString(html, "text/html");
        const sid = meta(doc, "j2-tab");
        const tok = meta(doc, "j2-token");
        document.querySelector<HTMLMetaElement>('meta[name="j2-tab"]')!.content = sid!;
        document.querySelector<HTMLMetaElement>('meta[name="j2-token"]')!.content = tok!;
        document.documentElement.setAttribute("data-j2-scope", doc.documentElement.getAttribute("data-j2-scope")!);
        document.title = doc.title;
        pending.clear();
        morpher.body(doc.body);
        clients.scan();
        remounting = false;
        connection.restart(sid, tok);
        retained.resumed(meta(doc, "j2-restored") === "1");
      })
      .catch(() => {
        remounting = false;
        setTimeout(() => remount(restoring), 2000);
      });
  }

  /** Failed claims of the browser cookie so far; the server offers a fresh URL after each, a few times. */
  let cookieClaimFailures = 0;

  function claimBrowserCookie(url: string): void {
    fetch(url, { credentials: "same-origin", cache: "no-store" })
      .then((r) => {
        if (!r.ok) {
          throw new Error("browser cookie claim answered " + r.status);
        }
        cookieClaimFailures = 0;
      })
      .catch(() => {
        if (cookieClaimFailures < 5) {
          setTimeout(() => send({ type: "browserCookieRetry" }), 1000 * 2 ** cookieClaimFailures++);
        }
      });
  }

  // What a pause would cut off: the server checks its own side (uploads, downloads, client calls).
  function busy(): boolean {
    const a = document.activeElement;
    return pending.size > 0 || uploads.size > 0
      || (!!a && a !== document.body && (a.matches("input, textarea, select") || (a as HTMLElement).isContentEditable))
      || Array.prototype.some.call(document.querySelectorAll("audio, video"), (m: HTMLMediaElement) => !m.paused && !m.ended);
  }

  window.addEventListener("pagehide", () => {
    if (connection.token) {
      remember(here(), connection.token);
    }
    if (connection.ready) {
      leaving = true;
      connection.sendIfReady({ type: "bye" });
    }
  });

  window.addEventListener("pageshow", (e) => {
    if (e.persisted && retained.phase !== "paused") {
      leaving = false;
      remount();
    }
  });

  navigation.listen(document);
  listenForEvents(document, { send: send, pending: pending, clients: clients, uploads: uploads });
  retained.listen(document);
  window.j2act = retained.api();

  // Clients mount from the props in the HTML at once; their calls into Java queue until the socket is up.
  clients.scan();
  if (previous.restore) {
    // A reopened tab, or Back from another site: ask for what the closed page kept.
    remount(previous.restore);
  } else {
    connection.connect();
    if (previous.forget) {
      send({ type: "forget", token: previous.forget });
    }
  }
}

/** A download link: the browser saves the response and the page stays (ADR 0012). */
function download(url: string): void {
  const a = document.createElement("a");
  a.href = url;
  a.download = "";
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

start();
