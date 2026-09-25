/*
 * j2act client runtime. Fixed and small: it forwards events, applies morph patches
 * with Idiomorph, and owns the purely client-side concerns the server cannot do in
 * time: debounce, pending UI and reconnect (ADR 0003, 0010, 0013). It also runs client
 * modules: their lifecycle, actions, callbacks and slots (ADR 0022).
 */
(() => {
  "use strict";

  function meta(name) {
    const m = document.querySelector('meta[name="' + name + '"]');
    return m ? m.content : null;
  }

  let sid = meta("j2-session");
  let tok = meta("j2-token");
  const wsPath = meta("j2-ws");
  const base = meta("j2-base") || "";
  if (!sid || !wsPath) {
    return;
  }

  let ws = null;
  let ready = false;
  let queue = [];
  let ackSeq = 0;
  let retry = 0;
  let leaving = false;
  let remounting = false;
  const inFlight = new Map();        // ack id -> { el, swap }
  const pendingEls = new Set();      // elements with a click or submit in flight
  const timers = new WeakMap();      // element -> debounce timer
  const throttles = new WeakMap();   // element -> { trailing } while its throttle interval runs

  const morphConfig = {
    morphStyle: "outerHTML",
    ignoreActiveValue: true,
    callbacks: {
      beforeAttributeUpdated: (name, el) => {
        if (pendingEls.has(el) && (name === "data-pending" || name === "aria-busy")) {
          return false;
        }
        // Form state the render did not set is the user's: keep it (ADR 0013).
        if ((name === "value" || name === "checked" || name === "selected") && el.hasAttribute("data-j2-ctl")) {
          return el.getAttribute("data-j2-ctl").split(" ").indexOf(name) >= 0;
        }
        return true;
      },
      // A client element keeps its node; its children are the client's (ADR 0022).
      beforeNodeMorphed: (oldNode, newNode) => {
        if (oldNode.nodeType === 1 && newNode.nodeType === 1 && oldNode.hasAttribute("data-j2-client")
          && oldNode.getAttribute("data-j2-client") === newNode.getAttribute("data-j2-client")) {
          syncClient(oldNode, newNode);
          return false;
        }
        return true;
      }
    }
  };
  const slotConfig = { morphStyle: "innerHTML", ignoreActiveValue: true, callbacks: morphConfig.callbacks };
  // Head morphs leave alone what the runtime added there: client stylesheets.
  const headConfig = {
    morphStyle: "innerHTML",
    callbacks: { beforeNodeRemoved: (node) => !(node.nodeType === 1 && node.hasAttribute("data-j2-keep")) }
  };

  // ---- socket

  function socketUrl() {
    return (location.protocol === "https:" ? "wss://" : "ws://") + location.host + wsPath;
  }

  function connect() {
    ws = new WebSocket(socketUrl());
    ws.onopen = () => {
      ws.send(JSON.stringify({ t: "hello", sid: sid, tok: tok }));
    };
    ws.onmessage = (e) => {
      onMessage(JSON.parse(e.data));
    };
    ws.onclose = () => {
      ready = false;
      if (leaving || remounting) {
        return;
      }
      const delay = Math.min(5000, 250 * Math.pow(2, retry++));
      setTimeout(connect, delay);
    };
  }

  function send(message) {
    const text = JSON.stringify(message);
    if (ready) {
      ws.send(text);
    } else {
      queue.push(text);
    }
  }

  function onMessage(m) {
    if (m.t === "ok") {
      ready = true;
      retry = 0;
      while (queue.length) {
        ws.send(queue.shift());
      }
    } else if (m.t === "patch") {
      patch(m.s, m.h, m.r === "1");
    } else if (m.t === "head") {
      Idiomorph.morph(document.head, m.h, headConfig);
    } else if (m.t === "url") {
      landed(m.u, m.m);
    } else if (m.t === "go") {
      location.assign(m.u);
    } else if (m.t === "dl") {
      download(m.u);
    } else if (m.t === "up") {
      upload(m.f, m.u);
    } else if (m.t === "upx") {
      // Refused before the first chunk: drop the file, reject an UploadTarget.send.
      files.delete(m.f);
      settleUpload(m.f, named("NotAllowedError", m.e));
    } else if (m.t === "wa") {
      // A window() call from Java, after the patch it came with.
      let result;
      try {
        result = runWeb(JSON.parse(m.w));
      } catch (e) {
        reply(m.i, false, e);
        return;
      }
      result.then((v) => reply(m.i, true, v), (e) => reply(m.i, false, e));
    } else if (m.t === "ca") {
      callAction(m);
    } else if (m.t === "ack") {
      ack(m.a);
    } else if (m.t === "expired") {
      remount();
    }
  }

  // ---- uploads (ADR 0006): the event names the file, the server answers with where to send it

  const files = new Map();           // file id -> File, until its upload ends
  let fileSeq = 0;
  const CHUNK = 512 * 1024;

  function pickedFile(input) {
    const file = input.files && input.files[0];
    if (!file) {
      return {};
    }
    const id = String(++fileSeq);
    files.set(id, file);
    return { fi: id, fn: file.name, fs: String(file.size), ft: file.type || "" };
  }

  // Sends the file in order; the server's offset decides where each chunk starts, so a
  // failed or repeated chunk resumes where the bytes really stopped.
  function upload(id, url) {
    const file = files.get(id);
    if (!file) {
      return; // gone after a reload: the server fails the run as stalled
    }
    let offset = 0;
    let failures = 0;
    const retry = () => {
      if (++failures > 8) {
        files.delete(id);
        settleUpload(id, named("NetworkError", "the upload kept failing"));
        return;
      }
      setTimeout(next, Math.min(5000, 250 * Math.pow(2, failures)));
    };
    const next = () => {
      fetch(url + "?o=" + offset, {
        method: "POST",
        credentials: "same-origin",
        headers: { "X-J2-Token": tok, "Content-Type": "application/octet-stream" },
        body: file.slice(offset, offset + CHUNK)
      }).then((r) => {
        return r.json().catch(() => ({})).then((j) => ({ status: r.status, body: j }));
      }).then((res) => {
        if ((res.status === 200 || res.status === 409) && typeof res.body.o === "number") {
          failures = 0;
          offset = res.body.o;
          if (res.body.done) {
            files.delete(id);
            settleUpload(id, null);
          } else {
            next();
          }
        } else if (res.status === 429) {
          retry(); // over the session's rate limit: back off, the bytes are still wanted
        } else {
          files.delete(id); // refused: the server already failed the run
          settleUpload(id, named("NotAllowedError", "the server refused the upload"));
        }
      }, retry);
    };
    next();
  }

  // A download link: the browser saves the response and the page stays (ADR 0012).
  function download(url) {
    const a = document.createElement("a");
    a.href = url;
    a.download = "";
    a.style.display = "none";
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  // ---- patches

  function patch(anchor, html, isRoot) {
    if (isRoot) {
      // The session root may be a new component after a navigation, with a new anchor.
      const doc = new DOMParser().parseFromString(html, "text/html");
      document.documentElement.setAttribute("data-j2s", anchor);
      Idiomorph.morph(document.head, doc.head.innerHTML, headConfig);
      Idiomorph.morph(document.body, doc.body, morphConfig);
    } else {
      const el = document.querySelector('[data-j2s="' + anchor + '"]');
      if (el) {
        Idiomorph.morph(el, html, morphConfig);
      } else if (liveAnchors.has(anchor)) {
        // Live content given to an action, and the client dropped it: the server stops patching it.
        liveAnchors.delete(anchor);
        console.warn("j2act: live content " + anchor + " passed to a client action left the document (ADR 0022)");
        send({ t: "lg", s: anchor });
      }
    }
    scanClients();
  }

  // ---- client modules (ADR 0022)

  const clients = new Map();         // client id -> { id, el, props, api, decoded, cleanup, ready, failed, waiting }
  const uploadWaiters = new Map();   // file id -> { resolve, reject } for an UploadTarget.send
  const liveAnchors = new Set();     // anchors of live components handed to actions
  const lostSlots = new Set();       // slot ids already reported as gone

  function named(name, message) {
    const e = new Error(message);
    e.name = name;
    return e;
  }

  function errorText(e) {
    return e && typeof e === "object" && e.message !== undefined ? (e.name || "Error") + ": " + e.message : "Error: " + e;
  }

  // Mount, update and cleanup failures and missing exports: the console here, the log on the server.
  function clientError(c, what, e) {
    console.error("j2act: client " + c.el.getAttribute("data-j2-export") + " failed in " + what, e);
    send({ t: "ce", c: c.id, e: what + ": " + errorText(e) });
  }

  function attributeNames(el) {
    return Array.from(el.attributes, (a) => a.name);
  }

  // Values from Java: callbacks become functions, slots elements, Upload targets objects with send().
  function revive(json) {
    return JSON.parse(json, (key, value) => {
      if (value && typeof value === "object" && !Array.isArray(value)) {
        if (typeof value.$fn === "string") {
          return callback(value.$fn);
        }
        if (typeof value.$slot === "string") {
          return document.querySelector('[data-j2-slot="' + CSS.escape(value.$slot) + '"]');
        }
        if (typeof value.$upload === "string") {
          return uploadTarget(value.$upload);
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
          const root = template.content.firstElementChild;
          liveAnchors.add(root.getAttribute("data-j2s"));
          return root;
        }
      }
      return value;
    });
  }

  function callback(id) {
    return (...args) => {
      send({ t: "cb", h: id, v: JSON.stringify(args) });
    };
  }

  // The declarative Upload of ADR 0006: resumable chunks and the server's limits, from JS.
  function uploadTarget(id) {
    return {
      send: (blob, name) => {
        return new Promise((resolve, reject) => {
          const fileId = String(++fileSeq);
          files.set(fileId, blob);
          uploadWaiters.set(fileId, { resolve: resolve, reject: reject });
          send({ t: "cb", h: id, v: "[]", fi: fileId, fn: name || blob.name || "blob", fs: String(blob.size),
            ft: blob.type || "" });
        });
      }
    };
  }

  function settleUpload(fileId, error) {
    const waiter = uploadWaiters.get(fileId);
    uploadWaiters.delete(fileId);
    if (waiter && error) {
      waiter.reject(error);
    } else if (waiter) {
      waiter.resolve();
    }
  }

  // After every patch: new client elements mount, changed props update, removed ones clean up.
  function scanClients() {
    document.querySelectorAll("[data-j2-client]").forEach(mountClient);
    clients.forEach((c) => {
      if (!c.el.isConnected || c.el.getAttribute("data-j2-client") !== c.id) {
        unmount(c);
      }
    });
  }

  function mountClient(el) {
    const id = el.getAttribute("data-j2-client");
    let c = clients.get(id);
    if (c && c.el === el) {
      propsChanged(c);
      return;
    }
    if (c) {
      unmount(c);
    }
    c = { id: id, el: el, props: el.getAttribute("data-j2-props"), api: null, decoded: null, cleanup: null,
      ready: false, failed: null, waiting: [] };
    clients.set(id, c);
    if (!el.__j2attrs) {
      el.__j2attrs = attributeNames(el);
    }
    const url = el.getAttribute("data-j2-module");
    const name = el.getAttribute("data-j2-export");
    const style = el.getAttribute("data-j2-css");
    // A TS build's stylesheet (CSS imports, CSS modules) is in place before mount runs.
    Promise.all([import(url), style ? stylesheet(style) : null]).then(([module]) => {
      if (clients.get(id) !== c) {
        return;
      }
      if (!module[name] || typeof module[name] !== "object") {
        throw named("ReferenceError", url + " has no export " + name);
      }
      c.api = module[name];
      start(c);
    }).catch((e) => {
      c.failed = e;
      clientError(c, "import", e);
      failWaiting(c, e);
    });
  }

  const stylesheets = new Map();   // url -> promise of its <link> having loaded

  function stylesheet(url) {
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

  function start(c) {
    try {
      c.decoded = c.props == null ? null : revive(c.props);
      if (typeof c.api.mount === "function") {
        const cleanup = c.props == null ? c.api.mount(c.el) : c.api.mount(c.el, c.decoded);
        c.cleanup = typeof cleanup === "function" ? cleanup : null;
      }
    } catch (e) {
      c.failed = e;
      clientError(c, "mount", e);
      failWaiting(c, e);
      return;
    }
    c.ready = true;
    const waiting = c.waiting;
    c.waiting = [];
    waiting.forEach((m) => { runAction(c, m); });
  }

  // update runs only when props really changed; without it the client mounts again.
  function propsChanged(c) {
    const props = c.el.getAttribute("data-j2-props");
    if (props === c.props) {
      return;
    }
    c.props = props;
    if (!c.ready) {
      return; // mount reads the latest props when its import lands
    }
    if (typeof c.api.update === "function") {
      const previous = c.decoded;
      try {
        c.decoded = revive(props);
        c.api.update(c.el, c.decoded, previous);
      } catch (e) {
        clientError(c, "update", e);
      }
    } else {
      stop(c);
      c.ready = false;
      start(c);
    }
  }

  function stop(c) {
    if (c.cleanup) {
      try {
        c.cleanup();
      } catch (e) {
        clientError(c, "cleanup", e);
      }
      c.cleanup = null;
    }
  }

  function unmount(c) {
    clients.delete(c.id);
    stop(c);
    failWaiting(c, named("AbortError", "the client unmounted"));
  }

  function failWaiting(c, e) {
    const waiting = c.waiting;
    c.waiting = [];
    waiting.forEach((m) => { reply(m.i, false, e); });
  }

  // Server attributes follow the render; attributes the client added stay; slots morph where they live.
  function syncClient(el, fresh) {
    const names = attributeNames(fresh);
    (el.__j2attrs || []).forEach((name) => {
      if (names.indexOf(name) < 0) {
        el.removeAttribute(name);
      }
    });
    names.forEach((name) => {
      const value = fresh.getAttribute(name);
      if (el.getAttribute(name) !== value) {
        el.setAttribute(name, value);
      }
    });
    el.__j2attrs = names;
    fresh.querySelectorAll(":scope > [data-j2-slot]").forEach((slot) => {
      const id = slot.getAttribute("data-j2-slot");
      const live = document.querySelector('[data-j2-slot="' + CSS.escape(id) + '"]');
      if (live) {
        Idiomorph.morph(live, slot.innerHTML, slotConfig);
      } else if (!lostSlots.has(id)) {
        lostSlots.add(id);
        console.warn("j2act: slot " + id + " left the document, so it stopped updating (ADR 0022)");
        send({ t: "ce", c: el.getAttribute("data-j2-client"), e: "slot " + id + " left the document, so it stopped updating" });
      }
    });
  }

  function invoke(c, name, args) {
    const fn = c.api[name];
    if (typeof fn !== "function") {
      throw named("TypeError", c.el.getAttribute("data-j2-export") + " has no action " + name);
    }
    return fn.apply(c.api, [c.el].concat(args));
  }

  // A call from Java, sent after the patch: queued until its client has mounted.
  function callAction(m) {
    const c = clients.get(m.c);
    if (!c) {
      reply(m.i, false, named("AbortError", "the client is not mounted"));
    } else if (c.failed) {
      reply(m.i, false, c.failed);
    } else if (!c.ready) {
      c.waiting.push(m);
    } else {
      runAction(c, m);
    }
  }

  function runAction(c, m) {
    let result;
    try {
      result = invoke(c, m.n, revive(m.a));
    } catch (e) {
      reply(m.i, false, e);
      return;
    }
    Promise.resolve(result).then((v) => reply(m.i, true, v), (e) => reply(m.i, false, e));
  }

  function reply(i, ok, value) {
    if (ok) {
      try {
        send({ t: "cr", i: i, ok: "1", v: JSON.stringify(value === undefined ? null : value) });
        return;
      } catch (e) {
        value = e;
      }
    }
    console.warn("j2act: client action failed", value);
    send({ t: "cr", i: i, ok: "0", e: errorText(value) });
  }

  // onClick(action, then): the action runs inside the click, so gesture-gated APIs work.
  // onClick/onSubmit/onKeyDown/onPointerDown/onPointerUp(action, then): the action runs
  // synchronously inside the event, which grants user activation, so gesture-gated APIs work.
  function boundAction(el, type, extra) {
    if (pendingEls.has(el)) {
      return;
    }
    const call = JSON.parse(el.getAttribute("data-j2-call-" + type));
    let result;
    try {
      if (call.w) {
        result = runWeb(call.w);
      } else {
        const c = clients.get(call.c);
        if (!c || !c.ready) {
          throw c && c.failed ? c.failed : named("InvalidStateError", "the client has not mounted yet");
        }
        result = invoke(c, call.n, revive(JSON.stringify(call.a)));
      }
    } catch (e) {
      console.warn("j2act: client action failed", e);
      dispatch(el, type, "", withError(extra, e));
      return;
    }
    Promise.resolve(result).then((v) => {
      dispatch(el, type, JSON.stringify(v === undefined ? null : v), extra);
    }, (e) => {
      console.warn("j2act: client action failed", e);
      dispatch(el, type, "", withError(extra, e));
    });
  }

  // ---- window() (ADR 0022): one generic executor that never grows with the facade

  // Follow the path (get a property or call a method at each step), then get, set, call or
  // construct. The first steps run synchronously, so a call bound to a gesture keeps it.
  function runWeb(w) {
    let target = window;
    for (const [kind, name, args] of w.p) {
      target = kind === "g" ? target[name] : target[name](...args);
      if (target == null) {
        throw named("TypeError", name + " is " + target);
      }
    }
    let value;
    if (w.k === "get") {
      value = target[w.n];
    } else if (w.k === "set") {
      target[w.n] = w.a[0];
    } else if (w.k === "new") {
      new target(...w.a);
    } else if (w.cb) {
      // Callback style (getCurrentPosition): resolve and reject go where the callbacks were.
      value = new Promise((resolve, reject) => {
        const args = w.a.slice();
        args[w.cb[0]] = resolve;
        if (w.cb.length > 1) {
          args[w.cb[1]] = (error) => reject(error instanceof Error ? error : named(errorName(error), error && error.message));
        }
        target[w.n](...args);
      });
    } else {
      value = target[w.n](...w.a);
    }
    return Promise.resolve(value).then((v) => (w.r ? project(v, w.r) : v));
  }

  // Copies what the snapshot's shape names; live objects never cross.
  function project(value, shape) {
    if (value == null) {
      return null;
    }
    if (shape === 1) {
      return value;
    }
    const out = {};
    for (const key of Object.keys(shape)) {
      out[key] = project(value[key], shape[key]);
    }
    return out;
  }

  // GeolocationPositionError is not a DOMException: name it from its code.
  function errorName(error) {
    const codes = { 1: "NotAllowedError", 2: "NotFoundError", 3: "TimeoutError" };
    return (error && codes[error.code]) || "Error";
  }

  function withError(extra, e) {
    const out = { x: errorText(e) };
    for (const name in extra || {}) {
      out[name] = extra[name];
    }
    return out;
  }

  // ---- soft navigation (ADR 0011): links and back/forward go over the socket

  let pendingHash = "";
  if ("scrollRestoration" in history) {
    history.scrollRestoration = "manual";
  }
  history.replaceState({ j2: 1, y: window.scrollY }, "");

  function inApp(url) {
    return url.origin === location.origin
      && (base === "" || url.pathname === base || url.pathname.indexOf(base + "/") === 0);
  }

  // ---- preload (ADR 0011): intent on a link asks the server to mount its target early

  const preloadByDefault = meta("j2-preload") === "intent";
  const preloadedAt = new Map();     // app URL -> when its preload was asked for
  let hoverTimer = null;

  function preloadUrl(target) {
    const a = target && target.closest ? target.closest("a[href]") : null;
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

  function preload(u) {
    const at = preloadedAt.get(u);
    if (at && Date.now() - at < 5000) {
      return;
    }
    preloadedAt.set(u, Date.now());
    send({ t: "pre", u: u });
  }

  document.addEventListener("mouseover", (e) => {
    const u = preloadUrl(e.target);
    clearTimeout(hoverTimer);
    if (u) {
      hoverTimer = setTimeout(() => preload(u), 50); // a pass-over is not intent
    }
  });
  ["focusin", "touchstart"].forEach((type) => {
    document.addEventListener(type, (e) => {
      const u = preloadUrl(e.target);
      if (u) {
        preload(u);
      }
    }, { passive: true });
  });

  function go(url, mode) {
    history.replaceState({ j2: 1, y: window.scrollY }, "");
    pendingHash = url.hash;
    send({ t: "nav", u: url.pathname + url.search, m: mode });
  }

  // The server answers a navigation with patches, then the final URL (after redirects).
  function landed(u, mode) {
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

  document.addEventListener("click", (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) {
      return;
    }
    const a = e.target.closest ? e.target.closest("a[href]") : null;
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

  // ---- events

  // extra carries event-specific fields (k, m for keys); pendingOn is the element whose
  // withPending markup swaps in, which for a submit is the submitter button.
  function dispatch(el, type, value, extra, pendingOn) {
    const blocking = type === "click" || type === "submit";
    if (blocking && pendingEls.has(el)) {
      return;
    }
    const handlerId = el.getAttribute("data-j2-" + type);
    if (!handlerId) {
      return;
    }
    const a = String(++ackSeq);
    if (blocking) {
      startPending(el, a, pendingOn || el);
    }
    const message = { t: "ev", h: handlerId, v: value == null ? "" : value, a: a };
    for (const name in extra || {}) {
      message[name] = extra[name];
    }
    send(message);
  }

  // Leading event at once, then at most one per interval: input and change send the latest
  // value when the interval ends, clicks inside it are dropped (ADR 0013).
  function throttle(el, type, ms) {
    let state = throttles.get(el);
    if (state) {
      if (type !== "click") {
        state.trailing = type;
      }
      return;
    }
    state = { trailing: null };
    throttles.set(el, state);
    dispatch(el, type, type === "click" ? null : el.value);
    const tick = () => {
      if (!state.trailing) {
        throttles.delete(el);
        return;
      }
      const next = state.trailing;
      state.trailing = null;
      dispatch(el, next, el.value);
      setTimeout(tick, ms);
    };
    setTimeout(tick, ms);
  }

  function schedule(el, type) {
    const throttleMs = parseInt(el.getAttribute("data-j2-throttle") || "-1", 10);
    if (throttleMs >= 0) {
      throttle(el, type, throttleMs);
      return;
    }
    const ms = parseInt(el.getAttribute("data-j2-debounce") || "-1", 10);
    const read = () => type === "click" ? null : el.value;
    if (ms < 0) {
      dispatch(el, type, read());
      return;
    }
    clearTimeout(timers.get(el));
    timers.set(el, setTimeout(() => {
      timers.delete(el);
      dispatch(el, type, read());
    }, ms));
  }

  document.addEventListener("click", (e) => {
    const el = e.target.closest ? e.target.closest("[data-j2-click]") : null;
    if (!el) {
      return;
    }
    if (el.tagName === "A" || el.type === "submit") {
      e.preventDefault();
    }
    if (el.hasAttribute("data-j2-call-click")) {
      boundAction(el, "click");
      return;
    }
    schedule(el, "click");
  });

  // Pointer events: pointerdown grants user activation to a mouse, pointerup to touch and pen.
  ["pointerdown", "pointerup"].forEach((type) => {
    document.addEventListener(type, (e) => {
      const el = e.target.closest ? e.target.closest("[data-j2-" + type + "]") : null;
      if (!el) {
        return;
      }
      const extra = { pt: e.pointerType, px: String(e.clientX), py: String(e.clientY) };
      if (el.hasAttribute("data-j2-call-" + type)) {
        boundAction(el, type, extra);
      } else {
        dispatch(el, type, "", extra);
      }
    });
  });

  ["input", "change"].forEach((type) => {
    document.addEventListener(type, (e) => {
      const el = e.target;
      if (!el || !el.hasAttribute || !el.hasAttribute("data-j2-" + type)) {
        return;
      }
      if (el.type === "file") {
        dispatch(el, type, "", pickedFile(el));
      } else {
        schedule(el, type);
      }
    });
  });

  // focus and blur do not bubble; focusin and focusout do.
  [["focusin", "focus"], ["focusout", "blur"]].forEach((pair) => {
    document.addEventListener(pair[0], (e) => {
      const el = e.target;
      if (el && el.hasAttribute && el.hasAttribute("data-j2-" + pair[1])) {
        dispatch(el, pair[1], el.value);
      }
    });
  });

  // Keys are filtered here, never debounced, so only the keys the server asked for travel.
  document.addEventListener("keydown", (e) => {
    const el = e.target;
    if (!el || !el.hasAttribute || !el.hasAttribute("data-j2-keydown")) {
      return;
    }
    const filter = el.getAttribute("data-j2-keys");
    if (filter && filter.split(" ").indexOf(e.key) < 0) {
      return;
    }
    const mods = (e.ctrlKey ? "c" : "") + (e.shiftKey ? "s" : "") + (e.altKey ? "a" : "") + (e.metaKey ? "m" : "");
    if (el.hasAttribute("data-j2-call-keydown")) {
      boundAction(el, "keydown", { k: e.key, m: mods });
      return;
    }
    dispatch(el, "keydown", el.value, { k: e.key, m: mods });
  });

  // The browser's own submit never happens; fields travel URL-encoded, file inputs excluded.
  document.addEventListener("submit", (e) => {
    const form = e.target;
    if (!form || !form.hasAttribute || !form.hasAttribute("data-j2-submit")) {
      return;
    }
    e.preventDefault();
    if (form.hasAttribute("data-j2-call-submit")) {
      boundAction(form, "submit");
      return;
    }
    const fields = new FormData(form);
    if (e.submitter && e.submitter.name) {
      fields.append(e.submitter.name, e.submitter.value);
    }
    const encoded = [];
    fields.forEach((value, name) => {
      if (typeof value === "string") {
        encoded.push(encodeURIComponent(name) + "=" + encodeURIComponent(value));
      }
    });
    dispatch(form, "submit", encoded.join("&"), null, e.submitter || form);
  });

  // ---- pending (ADR 0013): instant on click, reverted on ack unless a morph already replaced it

  function startPending(el, a, swap) {
    pendingEls.add(el);
    inFlight.set(a, { el: el, swap: swap });
    [el, swap].forEach((target) => {
      target.setAttribute("data-pending", "");
      target.setAttribute("aria-busy", "true");
    });
    const tpl = swap.querySelector(":scope > template[data-j2-pending]");
    if (tpl) {
      swap.__j2saved = swap.innerHTML;
      swap.innerHTML = tpl.outerHTML + "<span data-j2-pending-live>" + tpl.innerHTML + "</span>";
    }
  }

  function ack(a) {
    const entry = inFlight.get(a);
    inFlight.delete(a);
    if (!entry) {
      return;
    }
    pendingEls.delete(entry.el);
    [entry.el, entry.swap].forEach((target) => {
      target.removeAttribute("data-pending");
      target.removeAttribute("aria-busy");
    });
    const swap = entry.swap;
    if (swap.__j2saved != null && swap.querySelector(":scope > [data-j2-pending-live]")) {
      swap.innerHTML = swap.__j2saved;
    }
    swap.__j2saved = null;
  }

  // ---- session lifetime (ADR 0010)

  function remount() {
    if (remounting) {
      return;
    }
    remounting = true;
    ready = false;
    try {
      if (ws) {
        ws.close();
      }
    } catch (ignored) {
      // already closed
    }
    fetch(location.href, { credentials: "same-origin" })
      .then((r) => r.text())
      .then((html) => {
        const doc = new DOMParser().parseFromString(html, "text/html");
        const read = (name) => {
          const m = doc.querySelector('meta[name="' + name + '"]');
          return m ? m.content : null;
        };
        sid = read("j2-session");
        tok = read("j2-token");
        document.querySelector('meta[name="j2-session"]').content = sid;
        document.querySelector('meta[name="j2-token"]').content = tok;
        document.documentElement.setAttribute("data-j2s", doc.documentElement.getAttribute("data-j2s"));
        document.title = doc.title;
        inFlight.clear();
        pendingEls.clear();
        Idiomorph.morph(document.body, doc.body, morphConfig);
        scanClients();
        remounting = false;
        retry = 0;
        queue = [];
        connect();
      })
      .catch(() => {
        remounting = false;
        setTimeout(remount, 2000);
      });
  }

  window.addEventListener("pagehide", () => {
    if (ready) {
      leaving = true;
      ws.send(JSON.stringify({ t: "bye" }));
    }
  });

  window.addEventListener("pageshow", (e) => {
    if (e.persisted) {
      leaving = false;
      remount();
    }
  });

  // Clients mount from the props in the HTML at once; their calls into Java queue until the socket is up.
  scanClients();
  connect();
})();
