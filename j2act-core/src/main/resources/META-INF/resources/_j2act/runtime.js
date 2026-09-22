/*
 * j2act client runtime. Fixed and small: it forwards events, applies morph patches
 * with Idiomorph, and owns the purely client-side concerns the server cannot do in
 * time: debounce, pending UI and reconnect (ADR 0003, 0010, 0013).
 */
(function () {
  "use strict";

  function meta(name) {
    var m = document.querySelector('meta[name="' + name + '"]');
    return m ? m.content : null;
  }

  var sid = meta("j2-session");
  var tok = meta("j2-token");
  var wsPath = meta("j2-ws");
  if (!sid || !wsPath) {
    return;
  }

  var ws = null;
  var ready = false;
  var queue = [];
  var ackSeq = 0;
  var retry = 0;
  var leaving = false;
  var remounting = false;
  var inFlight = new Map();        // ack id -> element
  var pendingEls = new Set();      // elements with a click in flight
  var timers = new WeakMap();      // element -> debounce timer

  var morphConfig = {
    morphStyle: "outerHTML",
    ignoreActiveValue: true,
    callbacks: {
      beforeAttributeUpdated: function (name, el) {
        return !(pendingEls.has(el) && (name === "data-pending" || name === "aria-busy"));
      }
    }
  };

  // ---- socket

  function socketUrl() {
    return (location.protocol === "https:" ? "wss://" : "ws://") + location.host + wsPath;
  }

  function connect() {
    ws = new WebSocket(socketUrl());
    ws.onopen = function () {
      ws.send(JSON.stringify({ t: "hello", sid: sid, tok: tok }));
    };
    ws.onmessage = function (e) {
      onMessage(JSON.parse(e.data));
    };
    ws.onclose = function () {
      ready = false;
      if (leaving || remounting) {
        return;
      }
      var delay = Math.min(5000, 250 * Math.pow(2, retry++));
      setTimeout(connect, delay);
    };
  }

  function send(message) {
    var text = JSON.stringify(message);
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
      patch(m.s, m.h);
    } else if (m.t === "ack") {
      ack(m.a);
    } else if (m.t === "expired") {
      remount();
    }
  }

  // ---- patches

  function patch(anchor, html) {
    var el = document.querySelector('[data-j2s="' + anchor + '"]');
    if (!el) {
      return;
    }
    if (el === document.documentElement) {
      var doc = new DOMParser().parseFromString(html, "text/html");
      document.title = doc.title;
      Idiomorph.morph(document.body, doc.body, morphConfig);
      return;
    }
    Idiomorph.morph(el, html, morphConfig);
  }

  // ---- events

  function dispatch(el, type, value) {
    if (pendingEls.has(el)) {
      return;
    }
    var handlerId = el.getAttribute("data-j2-" + type);
    if (!handlerId) {
      return;
    }
    var a = String(++ackSeq);
    if (type === "click") {
      startPending(el, a);
    }
    send({ t: "ev", h: handlerId, v: value == null ? "" : value, a: a });
  }

  function schedule(el, type) {
    var ms = parseInt(el.getAttribute("data-j2-debounce") || "-1", 10);
    var read = function () { return type === "click" ? null : el.value; };
    if (ms < 0) {
      dispatch(el, type, read());
      return;
    }
    clearTimeout(timers.get(el));
    timers.set(el, setTimeout(function () {
      timers.delete(el);
      dispatch(el, type, read());
    }, ms));
  }

  document.addEventListener("click", function (e) {
    var el = e.target.closest ? e.target.closest("[data-j2-click]") : null;
    if (!el) {
      return;
    }
    if (el.tagName === "A" || el.type === "submit") {
      e.preventDefault();
    }
    schedule(el, "click");
  });

  ["input", "change"].forEach(function (type) {
    document.addEventListener(type, function (e) {
      var el = e.target;
      if (el && el.hasAttribute && el.hasAttribute("data-j2-" + type)) {
        schedule(el, type);
      }
    });
  });

  // ---- pending (ADR 0013): instant on click, reverted on ack unless a morph already replaced it

  function startPending(el, a) {
    pendingEls.add(el);
    inFlight.set(a, el);
    el.setAttribute("data-pending", "");
    el.setAttribute("aria-busy", "true");
    var tpl = el.querySelector(":scope > template[data-j2-pending]");
    if (tpl) {
      el.__j2saved = el.innerHTML;
      el.innerHTML = tpl.outerHTML + "<span data-j2-pending-live>" + tpl.innerHTML + "</span>";
    }
  }

  function ack(a) {
    var el = inFlight.get(a);
    inFlight.delete(a);
    if (!el) {
      return;
    }
    pendingEls.delete(el);
    el.removeAttribute("data-pending");
    el.removeAttribute("aria-busy");
    if (el.__j2saved != null && el.querySelector(":scope > [data-j2-pending-live]")) {
      el.innerHTML = el.__j2saved;
    }
    el.__j2saved = null;
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
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, "text/html");
        var read = function (name) {
          var m = doc.querySelector('meta[name="' + name + '"]');
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
        remounting = false;
        retry = 0;
        queue = [];
        connect();
      })
      .catch(function () {
        remounting = false;
        setTimeout(remount, 2000);
      });
  }

  window.addEventListener("pagehide", function () {
    if (ready) {
      leaving = true;
      ws.send(JSON.stringify({ t: "bye" }));
    }
  });

  window.addEventListener("pageshow", function (e) {
    if (e.persisted) {
      leaving = false;
      remount();
    }
  });

  connect();
})();
