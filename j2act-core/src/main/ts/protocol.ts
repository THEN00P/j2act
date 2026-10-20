/*
 * The socket protocol: flat JSON objects with a type. The server side is J2Act.onMessage and
 * the Json.object(...) calls that answer it.
 */

export type NavMode = "push" | "replace" | "pop";

/** Shape of a window() result (ADR 0022): true copies a value, an object copies the keys it names. */
export type Shape = true | { [key: string]: Shape };

/** A window() call: follow the path, then get, set, call or construct. */
export interface WebCall {
  /** Each step gets a property or calls a method with args. */
  path: [kind: "get" | "call", name: string, args?: unknown[]][];
  kind: "get" | "set" | "call" | "new";
  name?: string;
  args: unknown[];
  /** Callback-style calls: where resolve goes, then reject. */
  callbacks?: number[];
  shape?: Shape;
}

/** A client action Java calls, sent after the patch it came with. */
export interface ClientCall {
  type: "clientCall";
  id: string;
  client: string;
  action: string;
  /** A JSON array. */
  args: string;
}

/** A data-j2-call-* attribute: a window() call, or a client action with its arguments. */
export type BoundCall = { window: WebCall } | { window?: undefined; client: string; action: string; args: unknown[] };

export type ServerMessage =
  | { type: "ready" }
  | { type: "patch"; anchor: string; html: string; root?: true }
  | { type: "head"; html: string }
  | { type: "url"; url: string; mode: NavMode }
  | { type: "load"; url: string }
  | { type: "download"; url: string }
  | { type: "upload"; file: string; url: string }
  | { type: "uploadRefused"; file: string; error: string }
  | { type: "windowCall"; id: string; call: string }
  | ClientCall
  | { type: "ack"; id: string; ok: boolean }
  | { type: "expired"; saved?: boolean }
  | { type: "paused"; saved?: boolean }
  | { type: "pauseRefused" }
  | { type: "pauseRequested" }
  | { type: "modules"; modules: Record<string, string> }
  /** Fetch this to receive the browser cookie, first used over the socket (ADR 0030). */
  | { type: "browserCookie"; url: string };

/** Fields an event may add: keys, pointer, a picked file, a failed bound action. */
export interface EventFields {
  key?: string;
  ctrlKey?: boolean;
  shiftKey?: boolean;
  altKey?: boolean;
  metaKey?: boolean;
  pointerType?: string;
  clientX?: number;
  clientY?: number;
  fileId?: string;
  fileName?: string;
  fileSize?: number;
  fileType?: string;
  error?: string;
}

export type FileFields = Pick<EventFields, "fileId" | "fileName" | "fileSize" | "fileType">;

export type ClientMessage =
  | { type: "hello"; tab: string | null; token: string | null }
  | ({ type: "event"; id: string; handler: string; value: string } & EventFields)
  /** args is a JSON array. */
  | ({ type: "callback"; handler: string; args: string } & FileFields)
  | { type: "callResult"; id: string; ok: true; value: string }
  | { type: "callResult"; id: string; ok: false; error: string }
  | { type: "clientError"; client: string | null; error: string }
  | { type: "liveGone"; anchor: string }
  /** A patch kept an element the page lacks: the server renders that patch again in full (ADR 0031). */
  | { type: "resync"; anchor: string }
  | { type: "navigate"; url: string; mode: NavMode }
  | { type: "preload"; url: string }
  | { type: "pause"; automatic?: true }
  | { type: "bye" }
  /** The page's claim of its new browser cookie failed: the server offers it again (ADR 0030). */
  | { type: "browserCookieRetry" }
  /** A reload of a page whose closed tab kept a snapshot: the server deletes it (ADR 0030). */
  | { type: "forget"; token: string };

export type Send = (message: ClientMessage) => void;
