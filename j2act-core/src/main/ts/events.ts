import type { Clients } from "./clients.ts";
import { isTextField, type Fields } from "./fields.ts";
import type { Pending } from "./pending.ts";
import type { BoundCall, ClientMessage, EventFields, Send } from "./protocol.ts";
import type { Uploads } from "./uploads.ts";
import { runWeb } from "./web.ts";
import { errorText } from "./util.ts";

/*
 * Events (ADR 0013): delegated listeners on the document find the element whose render bound a
 * handler (data-j2-<type>) and send its value. Debounce, throttle, key filters and pending UI
 * happen here, since the server cannot do them in time.
 */

type Timer = ReturnType<typeof setTimeout>;

export interface EventsOptions {
  send: Send;
  pending: Pending;
  clients: Clients;
  uploads: Uploads;
  fields: Fields;
}

/** Listens on doc for every event a render can bind. */
export function listenForEvents(doc: Document, { send, pending, clients, uploads, fields }: EventsOptions): void {
  let ackSeq = 0;
  const undone = new WeakSet<Element>();                            // its last change was undo or redo
  const timers = new WeakMap<Element, { timer: Timer; type: string }>(); // element -> debounce timer
  const throttles = new WeakMap<Element, { trailing: string | null }>(); // while its interval runs

  /**
   * Sends the event to the handler the render bound. A click or submit has pending UI until its
   * ack, on pendingOn, which for a submit is the submitter button. Clicks queue, as one socket and
   * one lane keep them in order; a submit blocks its form, so a double submit dies here.
   */
  function dispatch(el: Element, type: string, value: string | null, extra?: EventFields, pendingOn?: Element): void {
    const showsPending = type === "click" || type === "submit";
    if (pending.blocks(el)) {
      return;
    }
    const handlerId = el.getAttribute("data-j2-" + type);
    if (!handlerId) {
      return;
    }
    const id = String(++ackSeq);
    if (showsPending) {
      pending.start(el, id, pendingOn || el, type === "submit");
    }
    if (isTextField(el)) {
      // Whatever event carries what the field holds, the server has now seen it. A bound action's carries its result.
      if (value === el.value && (el.getAttribute("data-j2-controlled") ?? "").split(" ").indexOf("value") >= 0) {
        fields.sent(el, Number(id), value);
        extra = { ...extra, echo: true };
      }
      if ((type === "input" || type === "change") && undone.delete(el)) {
        extra = { ...extra, undo: true };
      }
    }
    const message: ClientMessage = { type: "event", id: id, handler: handlerId, value: value == null ? "" : value, ...extra };
    send(message);
  }

  function schedule(el: Element, type: string): void {
    const throttleMs = parseInt(el.getAttribute("data-j2-throttle") || "-1", 10);
    if (throttleMs >= 0) {
      throttle(el, type, throttleMs);
      return;
    }
    const ms = parseInt(el.getAttribute("data-j2-debounce") || "-1", 10);
    const read = () => type === "click" ? null : valueOf(el);
    if (ms < 0) {
      dispatch(el, type, read());
      return;
    }
    clearTimeout(timers.get(el)?.timer);
    timers.set(el, {
      type: type,
      timer: setTimeout(() => {
        timers.delete(el);
        dispatch(el, type, read());
      }, ms)
    });
  }

  /** What still waits on a debounce or a throttle goes now, so the server hears it before what comes after it. */
  function sendWaiting(el: Element): void {
    const waiting = timers.get(el);
    if (waiting) {
      clearTimeout(waiting.timer);
      timers.delete(el);
      dispatch(el, waiting.type, waiting.type === "click" ? null : valueOf(el));
    }
    const running = throttles.get(el);
    if (running?.trailing) {
      const next = running.trailing;
      running.trailing = null;
      dispatch(el, next, valueOf(el));
    }
  }

  /**
   * The leading event at once, then at most one per interval: input and change send the latest
   * value when the interval ends, clicks inside it are dropped (ADR 0013).
   */
  function throttle(el: Element, type: string, ms: number): void {
    const state = throttles.get(el);
    if (state) {
      if (type !== "click") {
        state.trailing = type;
      }
      return;
    }
    const running = { trailing: null as string | null };
    throttles.set(el, running);
    dispatch(el, type, type === "click" ? null : valueOf(el));
    const tick = () => {
      if (!running.trailing) {
        throttles.delete(el);
        return;
      }
      const next = running.trailing;
      running.trailing = null;
      dispatch(el, next, valueOf(el));
      setTimeout(tick, ms);
    };
    setTimeout(tick, ms);
  }

  /**
   * onClick/onSubmit/onKeyDown/onPointerDown/onPointerUp(action, then): the action runs
   * synchronously inside the event, which grants user activation, so gesture-gated APIs work.
   * Its result, or its error, then goes to the handler like an event's value.
   */
  function boundAction(el: Element, type: string, extra?: EventFields): void {
    if (pending.blocks(el)) {
      return;
    }
    const call: BoundCall = JSON.parse(el.getAttribute("data-j2-call-" + type)!);
    let result;
    try {
      result = call.window ? runWeb(call.window) : clients.callNow(call.client, call.action, call.args);
    } catch (e) {
      console.warn("j2act: client action failed", e);
      dispatch(el, type, "", { ...extra, error: errorText(e) });
      return;
    }
    Promise.resolve(result).then((v) => {
      dispatch(el, type, JSON.stringify(v === undefined ? null : v), extra);
    }, (e) => {
      console.warn("j2act: client action failed", e);
      dispatch(el, type, "", { ...extra, error: errorText(e) });
    });
  }

  doc.addEventListener("click", (e) => {
    const el = closest(e.target, "[data-j2-click]");
    if (!el) {
      return;
    }
    if (el.tagName === "A" || (el as HTMLButtonElement).type === "submit") {
      e.preventDefault();
    }
    if (el.hasAttribute("data-j2-call-click")) {
      boundAction(el, "click");
      return;
    }
    schedule(el, "click");
  });

  // Pointer events: pointerdown grants user activation to a mouse, pointerup to touch and pen.
  for (const type of ["pointerdown", "pointerup"] as const) {
    doc.addEventListener(type, (e) => {
      const el = closest(e.target, "[data-j2-" + type + "]");
      if (!el) {
        return;
      }
      const extra = { pointerType: e.pointerType, clientX: e.clientX, clientY: e.clientY };
      if (el.hasAttribute("data-j2-call-" + type)) {
        boundAction(el, type, extra);
      } else {
        dispatch(el, type, "", extra);
      }
    });
  }

  fields.listen(doc);
  // What the server's merge sends back goes like typing, debounced or throttled as the field asks.
  fields.setResend((el) => schedule(el, "input"));
  for (const type of ["input", "change"]) {
    doc.addEventListener(type, (e) => {
      const el = bound(e.target, type);
      if (!el || fields.applying) {
        return;
      }
      if (type === "input") {
        const inputType = (e as InputEvent).inputType;
        if (inputType === "historyUndo" || inputType === "historyRedo") {
          undone.add(el);
        } else {
          undone.delete(el);
        }
      }
      if ((el as HTMLInputElement).type === "file") {
        dispatch(el, type, "", uploads.picked(el as HTMLInputElement));
      } else {
        schedule(el, type);
      }
    });
  }

  // focus and blur do not bubble; focusin and focusout do.
  for (const [listen, type] of [["focusin", "focus"], ["focusout", "blur"]]) {
    doc.addEventListener(listen, (e) => {
      const el = bound(e.target, type);
      if (el) {
        sendWaiting(el);
        dispatch(el, type, valueOf(el));
      }
    });
  }

  // Keys are filtered here, never debounced, so only the keys the server asked for travel.
  doc.addEventListener("keydown", (e) => {
    const el = bound(e.target, "keydown");
    if (!el) {
      return;
    }
    const filter = el.getAttribute("data-j2-keys");
    if (filter && filter.split(" ").indexOf(e.key) < 0) {
      return;
    }
    sendWaiting(el);
    const key = { key: e.key, ctrlKey: e.ctrlKey, shiftKey: e.shiftKey, altKey: e.altKey, metaKey: e.metaKey };
    if (el.hasAttribute("data-j2-call-keydown")) {
      boundAction(el, "keydown", key);
      return;
    }
    dispatch(el, "keydown", valueOf(el), key);
  });

  // The browser's own submit never happens; fields travel URL-encoded, file inputs excluded.
  doc.addEventListener("submit", (e) => {
    const form = bound(e.target, "submit") as HTMLFormElement | null;
    if (!form) {
      return;
    }
    e.preventDefault();
    if (form.hasAttribute("data-j2-call-submit")) {
      boundAction(form, "submit");
      return;
    }
    const submitter = e.submitter as HTMLButtonElement | null;
    const fields = new FormData(form);
    if (submitter && submitter.name) {
      fields.append(submitter.name, submitter.value);
    }
    const encoded: string[] = [];
    fields.forEach((value, name) => {
      if (typeof value === "string") {
        encoded.push(encodeURIComponent(name) + "=" + encodeURIComponent(value));
      }
    });
    dispatch(form, "submit", encoded.join("&"), undefined, submitter || form);
  });
}

function closest(target: EventTarget | null, selector: string): Element | null {
  return target instanceof Element ? target.closest(selector) : null;
}

/** The event target itself, if the render bound this type on it. */
function bound(target: EventTarget | null, type: string): Element | null {
  return target instanceof Element && target.hasAttribute("data-j2-" + type) ? target : null;
}

function valueOf(el: Element): string {
  return (el as HTMLInputElement).value;
}
