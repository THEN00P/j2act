import { createClients } from "./clients.ts";
import { createConnection } from "./connection.ts";
import { listenForEvents } from "./events.ts";
import { createMorpher } from "./morph.ts";
import { createNavigation } from "./navigation.ts";
import { createPending } from "./pending.ts";
import type { ClientMessage, ServerMessage } from "./protocol.ts";
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
type Handlers = { [T in ServerMessage["t"]]: (m: Extract<ServerMessage, { t: T }>) => void };

function start(): void {
  const sid = meta(document, "j2-session");
  const wsPath = meta(document, "j2-ws");
  const base = meta(document, "j2-base") || "";
  if (!sid || !wsPath) {
    return;
  }

  let leaving = false;
  let remounting = false;

  const connection = createConnection((location.protocol === "https:" ? "wss://" : "ws://") + location.host + wsPath,
    sid, meta(document, "j2-token"), onMessage,
    () => !leaving && !remounting && retained.phase !== "paused");
  const send = (message: ClientMessage) => connection.send(message);
  const pending = createPending();
  const uploads = createUploads(send, () => connection.tok);
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
    ok: () => {
      connection.opened();
      retained.connected();
    },
    patch: (m) => {
      if (!morpher.patch(m.s, m.h, m.r === "1")) {
        clients.anchorGone(m.s);
      }
      clients.scan();
    },
    head: (m) => morpher.head(m.h),
    url: (m) => navigation.landed(m.u, m.m),
    go: (m) => location.assign(m.u),
    dl: (m) => download(m.u),
    up: (m) => uploads.upload(m.f, m.u),
    upx: (m) => uploads.refused(m.f, m.e),
    // A window() call from Java, after the patch it came with.
    wa: (m) => {
      let result;
      try {
        result = runWeb(JSON.parse(m.w));
      } catch (e) {
        clients.reply(m.i, false, e);
        return;
      }
      result.then((v) => clients.reply(m.i, true, v), (e) => clients.reply(m.i, false, e));
    },
    ca: (m) => clients.call(m),
    ack: (m) => pending.ack(m.a),
    expired: () => {
      if (retained.expired()) {
        remount();
      }
    },
    paused: (m) => retained.paused(m.s === "1"),
    pausex: () => retained.refused(),
    rp: () => retained.requested(),
    mods: (m) => clients.reimport(m.m)
  };

  function onMessage(m: ServerMessage): void {
    (handlers[m.t] as ((m: ServerMessage) => void) | undefined)?.(m);
  }

  // ---- session lifetime (ADR 0010)

  function remount(): void {
    if (remounting) {
      return;
    }
    remounting = true;
    connection.close();
    // The old page's token lets the server restore its Retained State, if it kept a snapshot (ADR 0026).
    const tok = connection.tok;
    fetch(location.href, { credentials: "same-origin", headers: tok ? { "X-J2-Restore": tok } : {} })
      .then((r) => r.text())
      .then((html) => {
        const doc = new DOMParser().parseFromString(html, "text/html");
        const sid = meta(doc, "j2-session");
        const tok = meta(doc, "j2-token");
        document.querySelector<HTMLMetaElement>('meta[name="j2-session"]')!.content = sid!;
        document.querySelector<HTMLMetaElement>('meta[name="j2-token"]')!.content = tok!;
        document.documentElement.setAttribute("data-j2s", doc.documentElement.getAttribute("data-j2s")!);
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
        setTimeout(remount, 2000);
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
    if (connection.ready) {
      leaving = true;
      connection.sendIfReady({ t: "bye" });
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
  connection.connect();
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
