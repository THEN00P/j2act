import type { ClientMessage, ServerMessage } from "./protocol.ts";
import { backoff } from "./util.ts";

export type Connection = ReturnType<typeof createConnection>;

/**
 * The page's socket (ADR 0010): says hello with the tab id and token, queues messages until
 * the server answers ready, and reconnects with backoff unless the page is leaving or paused.
 */
export function createConnection(url: string, tab: string | null, token: string | null,
                                 onMessage: (m: ServerMessage) => void, reconnects: () => boolean) {
  let ready = false;
  let ws: WebSocket | null = null;
  let queue: string[] = [];
  let retry = 0;

  function connect(): void {
    const socket = new WebSocket(url);
    ws = socket;
    socket.onopen = () => {
      socket.send(JSON.stringify({ type: "hello", tab: tab, token: token } satisfies ClientMessage));
    };
    socket.onmessage = (e) => {
      onMessage(JSON.parse(e.data));
    };
    socket.onclose = () => {
      if (ws !== socket) {
        return; // a socket closed on purpose, after its successor opened
      }
      ready = false;
      if (reconnects()) {
        setTimeout(connect, backoff(retry++));
      }
    };
  }

  return {
    connect,

    /** The tab's token, which the page's HTTP requests carry too. */
    get token(): string | null {
      return token;
    },

    get ready(): boolean {
      return ready;
    },

    /** The server accepted the hello: send what queued meanwhile. */
    opened(): void {
      ready = true;
      retry = 0;
      while (queue.length) {
        ws!.send(queue.shift()!);
      }
    },

    /** Sends now, or once the socket is up. */
    send(message: ClientMessage): void {
      const text = JSON.stringify(message);
      if (ready) {
        ws!.send(text);
      } else {
        queue.push(text);
      }
    },

    /** Sends only if the socket is up: worth nothing once the moment passed. */
    sendIfReady(message: ClientMessage): void {
      if (ready) {
        ws!.send(JSON.stringify(message));
      }
    },

    /** The socket is about to close: queue from now on. */
    hold(): void {
      ready = false;
    },

    close(): void {
      ready = false;
      try {
        ws?.close();
      } catch {
        // already closed
      }
    },

    /** A new server-side tab: what queued for the old one is meaningless to it. */
    restart(newTab: string | null, newToken: string | null): void {
      tab = newTab;
      token = newToken;
      retry = 0;
      queue = [];
      connect();
    }
  };
}
