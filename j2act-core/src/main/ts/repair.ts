import type { ClientMessage } from "./protocol.ts";

const RETRY_MS = 2000;

/**
 * Asks for the whole page when the page has drifted from the server (ADR 0032). The server's rate limit refuses a
 * request without an answer, and what drifted was already acknowledged, so this asks until a whole page arrives.
 */
export function createRepair(send: (message: ClientMessage) => void) {
  let timer: ReturnType<typeof setInterval> | null = null;

  return {
    ask(): void {
      if (timer === null) {
        send({ type: "resync" });
        timer = setInterval(() => send({ type: "resync" }), RETRY_MS);
      }
    },

    arrived(): void {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    }
  };
}
