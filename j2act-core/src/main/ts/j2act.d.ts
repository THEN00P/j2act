/*
 * The j2act runtime as page scripts and client modules see it. Every j2act page has it; these
 * types only help editors and tsc, and plain JavaScript uses the same API without them. The
 * processor copies this file into .j2act/types (ADR 0028).
 */

/** window.j2act: pause and resume the page's session (ADR 0026). */
interface J2ActRuntime {
  /**
   * Saves the page's Retained State on the server and lets its session go. onPausing handlers
   * run first. Resolves true once paused, false when a handler failed or resume() called it off.
   */
  pause(): Promise<boolean>;
  /**
   * Runs the page again on a new session. Resolves true when its Retained State came back or it
   * had none, false when the snapshot was gone (expired, used, or someone else's). The page
   * runs either way.
   */
  resume(): Promise<boolean>;
  /**
   * Runs before every pause, which waits for the returned promise; a rejection calls the pause
   * off. The signal aborts when the pause is called off, as when a hidden tab is shown again.
   * Returns a function that removes the handler.
   */
  onPausing(handler: (signal: AbortSignal) => unknown): () => void;
}

declare var j2act: J2ActRuntime;

interface DocumentEventMap {
  /** The page paused: its session is gone until resume(). */
  "j2act:paused": Event;
  /** The page runs again; restored as resume() resolves. */
  "j2act:resumed": CustomEvent<{ restored: boolean }>;
}
