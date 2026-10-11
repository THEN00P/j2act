import type { ClientMessage, ServerMessage } from "./protocol.ts";
import { backoff } from "./util.ts";

/**
 * The page's socket (ADR 0010): says hello with the session id and token, queues messages until
 * the server answers ok, and reconnects with backoff unless the page is leaving or paused.
 */
export class Connection {
  sid: string | null;
  tok: string | null;
  ready = false;
  private ws: WebSocket | null = null;
  private queue: string[] = [];
  private retry = 0;
  private readonly url: string;
  private readonly onMessage: (m: ServerMessage) => void;
  private readonly reconnects: () => boolean;

  constructor(url: string, sid: string | null, tok: string | null, onMessage: (m: ServerMessage) => void,
              reconnects: () => boolean) {
    this.url = url;
    this.sid = sid;
    this.tok = tok;
    this.onMessage = onMessage;
    this.reconnects = reconnects;
  }

  connect(): void {
    const ws = new WebSocket(this.url);
    this.ws = ws;
    ws.onopen = () => {
      ws.send(JSON.stringify({ t: "hello", sid: this.sid, tok: this.tok } satisfies ClientMessage));
    };
    ws.onmessage = (e) => {
      this.onMessage(JSON.parse(e.data));
    };
    ws.onclose = () => {
      if (this.ws !== ws) {
        return; // a socket closed on purpose, after its successor opened
      }
      this.ready = false;
      if (this.reconnects()) {
        setTimeout(() => this.connect(), backoff(this.retry++));
      }
    };
  }

  /** The server accepted the hello: send what queued meanwhile. */
  opened(): void {
    this.ready = true;
    this.retry = 0;
    while (this.queue.length) {
      this.ws!.send(this.queue.shift()!);
    }
  }

  /** Sends now, or once the socket is up. */
  send(message: ClientMessage): void {
    const text = JSON.stringify(message);
    if (this.ready) {
      this.ws!.send(text);
    } else {
      this.queue.push(text);
    }
  }

  /** Sends only if the socket is up: worth nothing once the moment passed. */
  sendIfReady(message: ClientMessage): void {
    if (this.ready) {
      this.ws!.send(JSON.stringify(message));
    }
  }

  close(): void {
    this.ready = false;
    try {
      this.ws?.close();
    } catch {
      // already closed
    }
  }

  /** A new session: what queued for the old one is meaningless to it. */
  restart(sid: string | null, tok: string | null): void {
    this.sid = sid;
    this.tok = tok;
    this.retry = 0;
    this.queue = [];
    this.connect();
  }
}
