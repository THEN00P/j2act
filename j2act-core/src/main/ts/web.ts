import type { Shape, WebCall } from "./protocol.ts";
import { named } from "./util.ts";

/*
 * window() (ADR 0022): one generic executor that never grows with the facade. Java names the
 * path; this follows it.
 */

// The facade reaches any browser API, so its targets are untyped here; Java typed the call.
type Dynamic = any;

/**
 * Follows the path (get a property or call a method at each step), then gets, sets, calls or
 * constructs. Everything up to the last call runs synchronously, so a call bound to a gesture
 * keeps the gesture's user activation.
 */
export function runWeb(w: WebCall, root: object = window): Promise<unknown> {
  let target: Dynamic = root;
  for (const [kind, name, args] of w.p) {
    target = kind === "g" ? target[name] : target[name](...(args || []));
    if (target == null) {
      throw named("TypeError", name + " is " + target);
    }
  }
  let value: unknown;
  if (w.k === "get") {
    value = target[w.n!];
  } else if (w.k === "set") {
    target[w.n!] = w.a[0];
  } else if (w.k === "new") {
    new target(...w.a);
  } else if (w.cb) {
    // Callback style (getCurrentPosition): resolve and reject go where the callbacks were.
    const cb = w.cb;
    value = new Promise((resolve, reject) => {
      const args = w.a.slice();
      args[cb[0]] = resolve;
      if (cb.length > 1) {
        args[cb[1]] = (error: Dynamic) => reject(error instanceof Error ? error : named(errorName(error), error && error.message));
      }
      target[w.n!](...args);
    });
  } else {
    value = target[w.n!](...w.a);
  }
  return Promise.resolve(value).then((v) => (w.r ? project(v, w.r) : v));
}

/** Copies what the snapshot's shape names; live objects never cross. */
export function project(value: unknown, shape: Shape): unknown {
  if (value == null) {
    return null;
  }
  if (shape === 1) {
    return value;
  }
  const out: Record<string, unknown> = {};
  for (const key of Object.keys(shape)) {
    out[key] = project((value as Dynamic)[key], shape[key]);
  }
  return out;
}

const ERROR_CODES: Record<number, string> = { 1: "NotAllowedError", 2: "NotFoundError", 3: "TimeoutError" };

/** GeolocationPositionError is not a DOMException: name it from its code. */
export function errorName(error: { code?: number } | null | undefined): string {
  return (error && error.code !== undefined && ERROR_CODES[error.code]) || "Error";
}
