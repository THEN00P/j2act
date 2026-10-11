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
  /** Sends if the socket is up; a pause asked for while it is down goes out with the next "ok". */
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

export class Retained {
  private current: Phase = "running";
  /** The pause started because the tab was hidden: it gives way when busy, and the tab showing again resumes. */
  private byHiding = false;
  /** The server had Retained State to keep, so resume() reports whether it came back. */
  private withState = false;
  private deferral: AbortController | null = null;
  private handlers: ((signal: AbortSignal) => unknown)[] = [];
  private pauseWaiters: ((ok: boolean) => void)[] = [];
  private resumeWaiters: ((ok: boolean) => void)[] = [];
  private hiddenTimer: ReturnType<typeof setTimeout> | undefined;
  private readonly options: RetainedOptions;

  constructor(options: RetainedOptions) {
    this.options = options;
  }

  get phase(): Phase {
    return this.current;
  }

  /** window.j2act; see j2act.d.ts. */
  api(): J2ActRuntime {
    return Object.freeze({
      pause: () => this.pause(),
      resume: () => this.resume(),
      onPausing: (handler: (signal: AbortSignal) => unknown) => this.onPausing(handler)
    });
  }

  /** Hidden tabs pause after autoPauseAfter, and resume when shown, if the pause was theirs. */
  listen(doc: Document): void {
    if (this.options.autoPauseAfter <= 0) {
      return;
    }
    doc.addEventListener("visibilitychange", () => {
      clearTimeout(this.hiddenTimer);
      this.hiddenTimer = undefined;
      if (doc.hidden) {
        this.hiddenTimer = setTimeout(() => this.autoPause(), this.options.autoPauseAfter);
      } else if (this.current === "deferring" && this.byHiding) {
        this.cancel("the tab is visible again");
      } else if (this.current === "paused" && this.byHiding) {
        void this.resume();
      }
    });
  }

  /** As .NET's onCircuitPausing: runs before every pause and delays it until its promise settles. */
  onPausing(handler: (signal: AbortSignal) => unknown): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  /** Resolves true once the server saved the page's Retained State and let its session go. */
  pause(): Promise<boolean> {
    if (this.current === "paused") {
      return Promise.resolve(true);
    }
    return new Promise((resolve) => {
      this.pauseWaiters.push(resolve);
      if (this.current === "running") {
        this.begin(false);
      } else {
        this.byHiding = false; // asked for: it no longer gives way, nor resumes when the tab is shown
      }
    });
  }

  /** Resolves once the page runs again on a new session: true when its Retained State came back, or it had none. */
  resume(): Promise<boolean> {
    if (this.current === "deferring") {
      this.cancel("resumed");
      return Promise.resolve(true);
    }
    if (this.current === "pausing") {
      return new Promise((resolve) => this.pauseWaiters.push(() => this.resume().then(resolve)));
    }
    if (this.current !== "paused") {
      return Promise.resolve(false);
    }
    this.current = "running";
    this.byHiding = false;
    document.documentElement.removeAttribute("data-j2-paused");
    return new Promise((resolve) => {
      this.resumeWaiters.push(resolve);
      this.options.remount();
    });
  }

  /** The socket is up again: a pause that waited for it goes out now. */
  connected(): void {
    if (this.current === "pausing") {
      this.send();
    }
  }

  /** The server asked for a pause, as RequestCircuitPauseAsync does: handlers may delay it, not refuse it. */
  requested(): void {
    if (this.current === "running") {
      this.begin(false);
    }
  }

  /**
   * The session is gone. Evicted while a pause was on its way, a snapshot may be saved all the
   * same, so the page counts as paused. Returns whether the page should remount instead.
   */
  expired(): boolean {
    if (this.current === "pausing" || this.current === "deferring") {
      this.stopDeferral("the session ended");
      this.paused(true);
      return false;
    }
    return this.current === "running";
  }

  /** The server let the session go; saved: it had Retained State to keep. */
  paused(saved: boolean): void {
    this.withState = saved;
    this.current = "paused";
    this.options.stopSending();
    document.documentElement.setAttribute("data-j2-paused", "");
    document.dispatchEvent(new CustomEvent("j2act:paused"));
    this.settle(true);
    if (this.byHiding && !document.hidden) {
      void this.resume();
    }
  }

  /** The server was busy for an automatic pause. */
  refused(): void {
    if (!this.byHiding) {
      this.send(); // j2act.pause() was called meanwhile, and it does not give way
      return;
    }
    this.current = "running";
    this.byHiding = false;
    if (document.hidden) {
      this.hiddenTimer = setTimeout(() => this.autoPause(), RETRY_MS);
    }
  }

  /**
   * The page runs on its new session. As .NET's resumeCircuit(): false when the page had state to
   * keep and got none back, as when its snapshot expired, was used already or belongs to someone
   * else. The page runs either way.
   */
  resumed(restored: boolean): void {
    if (this.resumeWaiters.length) {
      const ok = restored || !this.withState;
      document.dispatchEvent(new CustomEvent("j2act:resumed", { detail: { restored: ok } }));
      this.resumeWaiters.splice(0).forEach((resolve) => resolve(ok));
    }
  }

  /** Runs the onPausing handlers, then asks the server; a handler that fails calls the pause off. */
  private begin(automatic: boolean): void {
    this.current = "deferring";
    this.byHiding = automatic;
    const controller = new AbortController();
    this.deferral = controller;
    Promise.all(this.handlers.map((h) => Promise.resolve().then(() => h(controller.signal)))).then(() => {
      if (this.deferral === controller) {
        this.deferral = null;
        this.current = "pausing";
        this.send(); // not connected: "ok" sends it once the socket is back
      }
    }, (e) => {
      if (this.deferral === controller) {
        console.warn("j2act: an onPausing handler failed, so the page did not pause", e);
        this.cancel("an onPausing handler failed");
      }
    });
  }

  private send(): void {
    this.options.sendIfReady(this.byHiding ? { t: "pause", a: "1" } : { t: "pause" });
  }

  private cancel(reason: string): void {
    this.stopDeferral(reason);
    this.current = "running";
    this.byHiding = false;
    this.settle(false);
  }

  private stopDeferral(reason: string): void {
    if (this.deferral) {
      this.deferral.abort(reason);
      this.deferral = null;
    }
  }

  private settle(ok: boolean): void {
    this.pauseWaiters.splice(0).forEach((resolve) => resolve(ok));
  }

  private autoPause(): void {
    this.hiddenTimer = undefined;
    if (!document.hidden || this.current !== "running" || this.options.remounting()) {
      return;
    }
    if (this.options.busy()) {
      this.hiddenTimer = setTimeout(() => this.autoPause(), RETRY_MS);
      return;
    }
    this.begin(true);
  }
}
