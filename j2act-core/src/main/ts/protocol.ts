/*
 * The socket protocol. Keys are one letter and flags are "1", since every message carries them.
 * The server side is J2Act.onMessage and the Json.object(...) calls that answer it.
 */

export type NavMode = "push" | "replace" | "pop";

/** Shape of a window() result (ADR 0022): 1 copies a value, an object copies the keys it names. */
export type Shape = 1 | { [key: string]: Shape };

/** A window() call: follow the path p, then get, set, call or construct. */
export interface WebCall {
  /** Each step gets a property ("g") or calls a method with args. */
  p: [kind: string, name: string, args?: unknown[]][];
  k: "get" | "set" | "call" | "new";
  n?: string;
  a: unknown[];
  /** Callback-style calls: where resolve goes, then reject. */
  cb?: number[];
  r?: Shape;
}

/** A client action Java calls, sent after the patch it came with. */
export interface ActionCall {
  t: "ca";
  i: string;
  /** Client id. */
  c: string;
  /** Action name. */
  n: string;
  /** Arguments, a JSON array. */
  a: string;
}

/** A data-j2-call-* attribute: a window() call, or a client action with its arguments. */
export type BoundCall = { w: WebCall } | { w?: undefined; c: string; n: string; a: unknown[] };

export type ServerMessage =
  | { t: "ok" }
  | { t: "patch"; s: string; h: string; r?: "1" }
  | { t: "head"; h: string }
  | { t: "url"; u: string; m: NavMode }
  | { t: "go"; u: string }
  | { t: "dl"; u: string }
  | { t: "up"; f: string; u: string }
  | { t: "upx"; f: string; e: string }
  | { t: "wa"; i: string; w: string }
  | ActionCall
  | { t: "ack"; a: string; ok?: "1" | "0" }
  | { t: "expired"; s?: "1" | "0" }
  | { t: "paused"; s?: "1" | "0" }
  | { t: "pausex" }
  | { t: "rp" }
  | { t: "mods"; m: Record<string, string> };

/** Fields an event may add: keys, pointer, a picked file, a failed bound action. */
export interface EventFields {
  k?: string;
  m?: string;
  pt?: string;
  px?: string;
  py?: string;
  fi?: string;
  fn?: string;
  fs?: string;
  ft?: string;
  x?: string;
}

export type FileFields = Pick<EventFields, "fi" | "fn" | "fs" | "ft">;

export type ClientMessage =
  | { t: "hello"; sid: string | null; tok: string | null }
  | ({ t: "ev"; h: string; v: string; a: string } & EventFields)
  | ({ t: "cb"; h: string; v: string } & FileFields)
  | { t: "cr"; i: string; ok: "1"; v: string }
  | { t: "cr"; i: string; ok: "0"; e: string }
  | { t: "ce"; c: string | null; e: string }
  | { t: "lg"; s: string }
  | { t: "nav"; u: string; m: NavMode }
  | { t: "pre"; u: string }
  | { t: "pause"; a?: "1" }
  | { t: "bye" };

export type Send = (message: ClientMessage) => void;
