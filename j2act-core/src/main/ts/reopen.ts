/*
 * Closed tabs come back (ADR 0030). Browsers restore a tab's sessionStorage when it is reopened
 * with Ctrl+Shift+T, as they do when Back returns to a page from another site, so it remembers
 * which server-side tab last showed each URL here. The server keeps that tab's snapshot for a
 * while after its goodbye; a page that loads by going back or forward asks for it, and a reload
 * tells the server to forget it. Any other load, such as a duplicated tab, which carries a copy of
 * sessionStorage, leaves it alone: the server never deletes the snapshot of a tab still live.
 */

const KEY = "j2act:tabs";
/** URLs remembered per browser tab, oldest dropped first. */
const MAX = 20;

type Tokens = Record<string, string>;

function read(): Tokens {
  try {
    const tokens: unknown = JSON.parse(sessionStorage.getItem(KEY) || "{}");
    return tokens && typeof tokens === "object" ? tokens as Tokens : {};
  } catch {
    return {}; // storage blocked or garbled: nothing to restore
  }
}

function write(tokens: Tokens): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(tokens));
  } catch {
    // storage blocked or full: the page just won't come back
  }
}

/** How this page load came about, as the browser reports it. */
export function navigationType(): string {
  const entry = performance.getEntriesByType?.("navigation")[0] as PerformanceNavigationTiming | undefined;
  return entry?.type ?? "navigate";
}

/** The token a closed page left for this URL: to restore when going back or forward, to forget on a reload. */
export function takePrevious(url: string, current: string | null, type: string): { restore?: string; forget?: string } {
  const tokens = read();
  const previous = tokens[url];
  if (!previous || previous === current) {
    return {};
  }
  delete tokens[url];
  write(tokens);
  return type === "back_forward" ? { restore: previous } : type === "reload" ? { forget: previous } : {};
}

/** On pagehide: this URL was last shown by the server-side tab with this token. */
export function remember(url: string, token: string): void {
  const tokens = read();
  for (const key of Object.keys(tokens)) {
    if (tokens[key] === token) {
      delete tokens[key]; // soft navigations moved this tab to another URL
    }
  }
  tokens[url] = token;
  const keys = Object.keys(tokens);
  for (let i = 0; i < keys.length - MAX; i++) {
    delete tokens[keys[i]!];
  }
  write(tokens);
}
