export function named(name: string, message?: string): Error {
  const e = new Error(message);
  e.name = name;
  return e;
}

export function errorText(e: unknown): string {
  if (e && typeof e === "object" && "message" in e && e.message !== undefined) {
    return ((e as Error).name || "Error") + ": " + e.message;
  }
  return "Error: " + e;
}

export function meta(doc: ParentNode, name: string): string | null {
  const m = doc.querySelector<HTMLMetaElement>('meta[name="' + name + '"]');
  return m ? m.content : null;
}

/**
 * Exponential backoff, capped at 5 s, with half of it random: after a server restart, every
 * open page reconnects, and without jitter they would all arrive in the same instant.
 */
export function backoff(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(5000, 250 * Math.pow(2, attempt));
  return Math.round(ceiling / 2 + random() * ceiling / 2);
}
