import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ClientMessage } from "../../main/ts/protocol.ts";
import { createRetained, type Retained } from "../../main/ts/retained.ts";

describe("Retained", () => {
  // Each test has its own; a Retained from an earlier test still listens on the document.
  let t: { sent: ClientMessage[]; remounts: number; ready: boolean; busy: boolean };
  let retained: Retained;

  function make(autoPauseAfter = 0): Retained {
    const own = t;
    return createRetained({
      sendIfReady: (m) => {
        if (own.ready) {
          own.sent.push(m);
        }
      },
      stopSending: () => {
        own.ready = false;
      },
      remount: () => {
        own.remounts++;
      },
      remounting: () => false,
      busy: () => own.busy,
      autoPauseAfter: autoPauseAfter
    });
  }

  function hidden(value: boolean): void {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => value });
    document.dispatchEvent(new Event("visibilitychange"));
  }

  beforeEach(() => {
    t = { sent: [], remounts: 0, ready: true, busy: false };
    retained = make();
    document.documentElement.removeAttribute("data-j2-paused");
  });

  afterEach(() => {
    vi.useRealTimers();
    hidden(false);
  });

  it("pauses after the onPausing handlers, once the server confirms", async () => {
    let release!: () => void;
    retained.onPausing(() => new Promise<void>((resolve) => {
      release = resolve;
    }));
    const paused = retained.pause();
    expect(retained.phase).toBe("deferring");
    await vi.waitFor(() => expect(release).toBeDefined());
    expect(t.sent).toEqual([]);

    release();
    await vi.waitFor(() => expect(retained.phase).toBe("pausing"));
    expect(t.sent).toEqual([{ type: "pause" }]);

    retained.paused(true);
    expect(await paused).toBe(true);
    expect(t.ready).toBe(false);
    expect(document.documentElement.hasAttribute("data-j2-paused")).toBe(true);
  });

  it("calls the pause off when a handler fails", async () => {
    retained.onPausing(() => Promise.reject(new Error("unsaved")));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(await retained.pause()).toBe(false);
    expect(retained.phase).toBe("running");
    expect(t.sent).toEqual([]);
    warn.mockRestore();
  });

  it("removes a handler", async () => {
    const remove = retained.onPausing(() => Promise.reject(new Error("never")));
    remove();
    retained.pause();
    await vi.waitFor(() => expect(t.sent).toEqual([{ type: "pause" }]));
  });

  it("aborts the handlers' signal when resumed while deferring", async () => {
    let signal!: AbortSignal;
    retained.onPausing((s) => {
      signal = s;
      return new Promise(() => {});
    });
    const paused = retained.pause();
    await vi.waitFor(() => expect(signal).toBeDefined());
    expect(await retained.resume()).toBe(true);
    expect(signal.aborted).toBe(true);
    expect(await paused).toBe(false);
  });

  it("sends a pause that waited for the socket once it is back", async () => {
    t.ready = false;
    retained.pause();
    await vi.waitFor(() => expect(retained.phase).toBe("pausing"));
    expect(t.sent).toEqual([]);
    t.ready = true;
    retained.connected();
    expect(t.sent).toEqual([{ type: "pause" }]);
  });

  it("resumes through a remount and reports whether the state came back", async () => {
    retained.pause();
    await vi.waitFor(() => expect(retained.phase).toBe("pausing"));
    retained.paused(true);

    const resumed = retained.resume();
    expect(t.remounts).toBe(1);
    expect(retained.phase).toBe("running");
    retained.resumed(false);
    expect(await resumed).toBe(false);
  });

  it("reports a resume as restored when the page had no state to keep", async () => {
    retained.pause();
    await vi.waitFor(() => expect(retained.phase).toBe("pausing"));
    retained.paused(false);
    const resumed = retained.resume();
    retained.resumed(false);
    expect(await resumed).toBe(true);
  });

  it("counts a tab that expired mid-pause as paused, and otherwise remounts", async () => {
    expect(retained.expired()).toBe(true);
    retained.pause();
    expect(retained.expired()).toBe(false);
    expect(retained.phase).toBe("paused");
    expect(retained.expired()).toBe(false);
  });

  it("pauses a hidden tab on its own, gives way when refused, and resumes when shown", async () => {
    vi.useFakeTimers();
    retained = make(1000);
    retained.listen(document);
    hidden(true);
    await vi.advanceTimersByTimeAsync(1000);
    expect(t.sent).toEqual([{ type: "pause", automatic: true }]);

    retained.refused();
    expect(retained.phase).toBe("running");
    await vi.advanceTimersByTimeAsync(10000);
    expect(t.sent).toEqual([{ type: "pause", automatic: true }, { type: "pause", automatic: true }]);

    retained.paused(true);
    hidden(false);
    expect(t.remounts).toBe(1);
  });

  it("waits while the page is busy", async () => {
    vi.useFakeTimers();
    t.busy = true;
    retained = make(1000);
    retained.listen(document);
    hidden(true);
    await vi.advanceTimersByTimeAsync(1000);
    expect(t.sent).toEqual([]);
    t.busy = false;
    await vi.advanceTimersByTimeAsync(10000);
    expect(t.sent).toEqual([{ type: "pause", automatic: true }]);
  });

  it("does not give way once pause() was called during an automatic pause", async () => {
    vi.useFakeTimers();
    retained = make(1000);
    retained.listen(document);
    hidden(true);
    await vi.advanceTimersByTimeAsync(1000);
    retained.pause();
    retained.refused();
    expect(t.sent).toEqual([{ type: "pause", automatic: true }, { type: "pause" }]);
  });
});
