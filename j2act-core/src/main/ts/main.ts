import { createClients } from "./clients.ts";
import { createConnection } from "./connection.ts";
import { listenForEvents } from "./events.ts";
import { createMorpher } from "./morph.ts";
import { createNavigation } from "./navigation.ts";
import { createPending } from "./pending.ts";
import { createRepair } from "./repair.ts";
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
    () => !leaving && !remounting && retained.phase !== "paused", () => applied);
  const send = (message: ClientMessage) => connection.send(message);
  document.addEventListener("visibilitychange", () => {
    if (connection.ready) {
      send({ type: "visibility", hidden: document.hidden });
    }
  });
  const pending = createPending();
  const uploads = createUploads(send, () => connection.token);
  const morpher = createMorpher(pending, (el, fresh) => clients.sync(el, fresh));
  const repair = createRepair((message) => connection.sendIfReady(message));
  const clients = createClients({ send: send, connected: () => connection.ready, uploads: uploads, morphSlot: (live, html) => morpher.slot(live, html) });
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
    ready: (m) => {
      connection.opened();
      clients.resendResults(m.maxFrame);
      acknowledgeSoon();
      retained.connected();
      if (document.hidden) {
        send({ type: "visibility", hidden: true });
      }
    },
    patch: (m) => {
      if (!morpher.patch(m.anchor, m.html, m.root === true) && !clients.anchorGone(m.anchor)) {
        // Its place is not on the page, so the page has drifted from the server (ADR 0032).
        repair.ask();
      }
      if (m.root === true) {
        repair.arrived();
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
      const answer = clients.replier(m.id);
      result.then((v) => answer(true, v), (e) => answer(false, e));
    },
    clientCall: (m) => clients.call(m),
    ack: (m) => pending.ack(m.id),
    callSeen: (m) => clients.resultSeen(m.id),
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

  /** The highest message number applied, which a resend starts after (ADR 0032). */
  let applied = 0;
  let acknowledging = false;

  /** Acknowledgements are batched: one covers everything up to its number. */
  function acknowledgeSoon(): void {
    if (!acknowledging) {
      acknowledging = true;
      setTimeout(() => {
        acknowledging = false;
        connection.sendIfReady({ type: "seen", n: applied });
      }, 100);
    }
  }

  function onMessage(m: ServerMessage & { n?: number }): void {
    const n = m.n;
    if (n !== undefined) {
      if (n <= applied) {
        return; // a resend of what an acknowledgement lost on its way did not cover
      }
      if (n > applied + 1) {
        repair.ask(); // a gap nothing can fill: the whole page, morphed in
      }
    }
    try {
      (handlers[m.type] as ((m: ServerMessage) => void) | undefined)?.(m);
    } finally {
      if (n !== undefined) {
        applied = n;
        acknowledgeSoon();
      }
    }
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
        applied = 0;
        clients.forgetResults();
        repair.arrived();
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

  /** Claims run one after another, so the latest offer's cookie is the one that lands last. */
  let claims: Promise<void> = Promise.resolve();

  function claimBrowserCookie(url: string): void {
    claims = claims.then(() => fetch(url, { credentials: "same-origin", cache: "no-store" })
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
      }));
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
