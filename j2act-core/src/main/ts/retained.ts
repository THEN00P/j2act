import type { ClientMessage } from "./protocol.ts";

/*
 * Pause and resume (ADR 0026): the page's Retained State stays on the server as a snapshot and
 * its session is freed, as .NET's circuit pause. running -> deferring (onPausing handlers run)
 * -> pausing (asked the server) -> paused; resume() goes back through a remount.
 */

export type Phase = "running" | "deferring" | "pausing" | "paused";

/** How long a hidden tab waits before trying again, after the server or the page was busy. */
const RETRY_MS = 10000;

export interface RetainedOptions {
  /** Sends if the socket is up; a pause asked for while it is down goes out with the next "ready". */
  sendIfReady: (message: ClientMessage) => void;
  /** The socket is about to close: queue from now on. */
  stopSending: () => void;
  /** Starts the page again on a new session; resumed() follows. */
  remount: () => void;
  remounting: () => boolean;
  /** What a pause would cut off on this side: the server checks its own (uploads, downloads, client calls). */
  busy: () => boolean;
  /** Milliseconds a tab stays hidden before it pauses on its own; 0 never. */
  autoPauseAfter: number;
}

export type Retained = ReturnType<typeof createRetained>;

export function createRetained(options: RetainedOptions) {
  let current: Phase = "running";
  /** The pause started because the tab was hidden: it gives way when busy, and the tab showing again resumes. */
  let byHiding = false;
  /** The server had Retained State to keep, so resume() reports whether it came back. */
  let withState = false;
  let deferral: AbortController | null = null;
  let handlers: ((signal: AbortSignal) => unknown)[] = [];
  const pauseWaiters: ((ok: boolean) => void)[] = [];
  const resumeWaiters: ((ok: boolean) => void)[] = [];
  let hiddenTimer: ReturnType<typeof setTimeout> | undefined;

  /** As .NET's onCircuitPausing: runs before every pause and delays it until its promise settles. */
  function onPausing(handler: (signal: AbortSignal) => unknown): () => void {
    handlers.push(handler);
    return () => {
      handlers = handlers.filter((h) => h !== handler);
    };
  }

  /** Resolves true once the server saved the page's Retained State and let its session go. */
  function pause(): Promise<boolean> {
    if (current === "paused") {
      return Promise.resolve(true);
    }
    return new Promise((resolve) => {
      pauseWaiters.push(resolve);
      if (current === "running") {
        begin(false);
      } else {
        byHiding = false; // asked for: it no longer gives way, nor resumes when the tab is shown
      }
    });
  }

  /** Resolves once the page runs again on a new session: true when its Retained State came back, or it had none. */
  function resume(): Promise<boolean> {
    if (current === "deferring") {
      cancel("resumed");
      return Promise.resolve(true);
    }
    if (current === "pausing") {
      return new Promise((resolve) => pauseWaiters.push(() => resume().then(resolve)));
    }
    if (current !== "paused") {
      return Promise.resolve(false);
    }
    current = "running";
    byHiding = false;
    document.documentElement.removeAttribute("data-j2-paused");
    return new Promise((resolve) => {
      resumeWaiters.push(resolve);
      options.remount();
    });
  }

  /** The server let the session go; saved: it had Retained State to keep. */
  function paused(saved: boolean): void {
    withState = saved;
    current = "paused";
    options.stopSending();
    document.documentElement.setAttribute("data-j2-paused", "");
    document.dispatchEvent(new CustomEvent("j2act:paused"));
    settle(true);
    if (byHiding && !document.hidden) {
      void resume();
    }
  }

  /** Runs the onPausing handlers, then asks the server; a handler that fails calls the pause off. */
  function begin(automatic: boolean): void {
    current = "deferring";
    byHiding = automatic;
    const controller = new AbortController();
    deferral = controller;
    Promise.all(handlers.map((h) => Promise.resolve().then(() => h(controller.signal)))).then(() => {
      if (deferral === controller) {
        deferral = null;
        current = "pausing";
        send(); // not connected: "ready" sends it once the socket is back
      }
    }, (e) => {
      if (deferral === controller) {
        console.warn("j2act: an onPausing handler failed, so the page did not pause", e);
        cancel("an onPausing handler failed");
      }
    });
  }

  function send(): void {
    options.sendIfReady(byHiding ? { type: "pause", automatic: true } : { type: "pause" });
  }

  function cancel(reason: string): void {
    stopDeferral(reason);
    current = "running";
    byHiding = false;
    settle(false);
  }

  function stopDeferral(reason: string): void {
    if (deferral) {
      deferral.abort(reason);
      deferral = null;
    }
  }

  function settle(ok: boolean): void {
    pauseWaiters.splice(0).forEach((resolve) => resolve(ok));
  }

  function autoPause(): void {
    hiddenTimer = undefined;
    if (!document.hidden || current !== "running" || options.remounting()) {
      return;
    }
    if (options.busy()) {
      hiddenTimer = setTimeout(autoPause, RETRY_MS);
      return;
    }
    begin(true);
  }

  return {
    get phase(): Phase {
      return current;
    },

    onPausing,
    pause,
    resume,
    paused,

    /** window.j2act; see j2act.d.ts. */
    api(): J2ActRuntime {
      return Object.freeze({ pause, resume, onPausing });
    },

    /** Hidden tabs pause after autoPauseAfter, and resume when shown, if the pause was theirs. */
    listen(doc: Document): void {
      if (options.autoPauseAfter <= 0) {
        return;
      }
      doc.addEventListener("visibilitychange", () => {
        clearTimeout(hiddenTimer);
        hiddenTimer = undefined;
        if (doc.hidden) {
          hiddenTimer = setTimeout(autoPause, options.autoPauseAfter);
        } else if (current === "deferring" && byHiding) {
          cancel("the tab is visible again");
        } else if (current === "paused" && byHiding) {
          void resume();
        }
      });
    },

    /** The socket is up again: a pause that waited for it goes out now. */
    connected(): void {
      if (current === "pausing") {
        send();
      }
    },

    /** The server asked for a pause, as RequestCircuitPauseAsync does: handlers may delay it, not refuse it. */
    requested(): void {
      if (current === "running") {
        begin(false);
      }
    },

    /**
     * The session is gone. Evicted while a pause was on its way, a snapshot may be saved all the
     * same, so the page counts as paused. Returns whether the page should remount instead.
     */
    expired(): boolean {
      if (current === "pausing" || current === "deferring") {
        stopDeferral("the session ended");
        paused(true);
        return false;
      }
      return current === "running";
    },

    /** The server was busy for an automatic pause. */
    refused(): void {
      if (!byHiding) {
        send(); // j2act.pause() was called meanwhile, and it does not give way
        return;
      }
      current = "running";
      byHiding = false;
      if (document.hidden) {
        hiddenTimer = setTimeout(autoPause, RETRY_MS);
      }
    },

    /**
     * The page runs on its new session. As .NET's resumeCircuit(): false when the page had state to
     * keep and got none back, as when its snapshot expired, was used already or belongs to someone
     * else. The page runs either way.
     */
    resumed(restored: boolean): void {
      if (resumeWaiters.length) {
        const ok = restored || !withState;
        document.dispatchEvent(new CustomEvent("j2act:resumed", { detail: { restored: ok } }));
        resumeWaiters.splice(0).forEach((resolve) => resolve(ok));
      }
    }
  };
}
