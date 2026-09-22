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
  var inFlight = new Map();        // ack id -> { el, swap }
  var pendingEls = new Set();      // elements with a click or submit in flight
  var timers = new WeakMap();      // element -> debounce timer

  var morphConfig = {
    morphStyle: "outerHTML",
    ignoreActiveValue: true,
    callbacks: {
      beforeAttributeUpdated: function (name, el) {
        if (pendingEls.has(el) && (name === "data-pending" || name === "aria-busy")) {
          return false;
        }
        // Form state the render did not set is the user's: keep it (ADR 0013).
        if ((name === "value" || name === "checked" || name === "selected") && el.hasAttribute("data-j2-ctl")) {
          return el.getAttribute("data-j2-ctl").split(" ").indexOf(name) >= 0;
        }
        return true;
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

  // extra carries event-specific fields (k, m for keys); pendingOn is the element whose
  // withPending markup swaps in, which for a submit is the submitter button.
  function dispatch(el, type, value, extra, pendingOn) {
    var blocking = type === "click" || type === "submit";
    if (blocking && pendingEls.has(el)) {
      return;
    }
    var handlerId = el.getAttribute("data-j2-" + type);
    if (!handlerId) {
      return;
    }
    var a = String(++ackSeq);
    if (blocking) {
      startPending(el, a, pendingOn || el);
    }
    var message = { t: "ev", h: handlerId, v: value == null ? "" : value, a: a };
    for (var name in extra || {}) {
      message[name] = extra[name];
    }
    send(message);
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

  // focus and blur do not bubble; focusin and focusout do.
  [["focusin", "focus"], ["focusout", "blur"]].forEach(function (pair) {
    document.addEventListener(pair[0], function (e) {
      var el = e.target;
      if (el && el.hasAttribute && el.hasAttribute("data-j2-" + pair[1])) {
        dispatch(el, pair[1], el.value);
      }
    });
  });

  // Keys are filtered here, never debounced, so only the keys the server asked for travel.
  document.addEventListener("keydown", function (e) {
    var el = e.target;
    if (!el || !el.hasAttribute || !el.hasAttribute("data-j2-keydown")) {
      return;
    }
    var filter = el.getAttribute("data-j2-keys");
    if (filter && filter.split(" ").indexOf(e.key) < 0) {
      return;
    }
    var mods = (e.ctrlKey ? "c" : "") + (e.shiftKey ? "s" : "") + (e.altKey ? "a" : "") + (e.metaKey ? "m" : "");
    dispatch(el, "keydown", el.value, { k: e.key, m: mods });
  });

  // The browser's own submit never happens; fields travel URL-encoded, file inputs excluded.
  document.addEventListener("submit", function (e) {
    var form = e.target;
    if (!form || !form.hasAttribute || !form.hasAttribute("data-j2-submit")) {
      return;
    }
    e.preventDefault();
    var fields = new FormData(form);
    if (e.submitter && e.submitter.name) {
      fields.append(e.submitter.name, e.submitter.value);
    }
    var encoded = [];
    fields.forEach(function (value, name) {
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
    [el, swap].forEach(function (target) {
      target.setAttribute("data-pending", "");
      target.setAttribute("aria-busy", "true");
    });
    var tpl = swap.querySelector(":scope > template[data-j2-pending]");
    if (tpl) {
      swap.__j2saved = swap.innerHTML;
      swap.innerHTML = tpl.outerHTML + "<span data-j2-pending-live>" + tpl.innerHTML + "</span>";
    }
  }

  function ack(a) {
    var entry = inFlight.get(a);
    inFlight.delete(a);
    if (!entry) {
      return;
    }
    pendingEls.delete(entry.el);
    [entry.el, entry.swap].forEach(function (target) {
      target.removeAttribute("data-pending");
      target.removeAttribute("aria-busy");
    });
    var swap = entry.swap;
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
