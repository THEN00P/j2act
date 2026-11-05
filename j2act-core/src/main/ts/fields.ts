export type Fields = ReturnType<typeof createFields>;

/** Replace base[from, to) with text. */
export interface Edit {
  from: number;
  to: number;
  text: string;
}

type TextField = HTMLInputElement | HTMLTextAreaElement;

/** The one changed span between two strings, from the first difference to the last. */
function span(base: string, next: string): Edit {
  let from = 0;
  while (from < base.length && from < next.length && base[from] === next[from]) {
    from++;
  }
  let tail = 0;
  while (tail < base.length - from && tail < next.length - from
    && base[base.length - 1 - tail] === next[next.length - 1 - tail]) {
    tail++;
  }
  return { from: from, to: base.length - tail, text: next.slice(from, next.length - tail) };
}

/**
 * What to do to mine to bring in the server's change (ADR 0034): base is what the server had seen,
 * server its answer. Null when it changed nothing, or when it touches what the user changed since,
 * who wins for now. Touching is fine only where one side replaced text and the other added beside it.
 */
export function merge(base: string, server: string, mine: string): Edit | null {
  const theirs = span(base, server);
  if (theirs.from === theirs.to && theirs.text === "") {
    return null;
  }
  const ours = span(base, mine);
  if (ours.from === ours.to && ours.text === "") {
    return theirs;
  }
  if (server === mine) {
    return null;
  }
  const oursAdds = ours.from === ours.to;
  const theirsAdds = theirs.from === theirs.to;
  if (theirs.to < ours.from || (theirs.to === ours.from && oursAdds && !theirsAdds)) {
    return theirs;
  }
  if (ours.to < theirs.from || (ours.to === theirs.from && oursAdds && !theirsAdds)) {
    const shift = ours.text.length - (ours.to - ours.from);
    return { from: theirs.from + shift, to: theirs.to + shift, text: theirs.text };
  }
  return null;
}

export function applyEdit(value: string, edit: Edit | null): string {
  return edit ? value.slice(0, edit.from) + edit.text + value.slice(edit.to) : value;
}

/** Where a caret goes when this edit is made around it. */
function moved(caret: number, edit: Edit): number {
  if (caret <= edit.from) {
    return caret;
  }
  if (caret >= edit.to) {
    return caret + edit.text.length - (edit.to - edit.from);
  }
  return edit.from + edit.text.length;
}

const WITH_CARET = new Set(["text", "search", "url", "tel", "password"]);

/** A field whose value is text the user edits, so a server's change to it has to be merged. */
export function isTextField(el: Element): el is TextField {
  if (el.tagName === "TEXTAREA") {
    return true;
  }
  return el.tagName === "INPUT" && (WITH_CARET.has((el as HTMLInputElement).type) || hasNoCaret(el));
}

/** Text with no selection API, so there is no caret to keep and no span to make an edit in. */
function hasNoCaret(el: Element): boolean {
  const type = (el as HTMLInputElement).type;
  return type === "email" || type === "number";
}

/**
 * Text fields the server controls (ADR 0034): what was sent for each, and the server's answers
 * merged into what the field holds now.
 */
export function createFields() {
  /** What was sent and not yet answered for; the first may be what the server last saw. */
  interface State {
    sent: { id: number; value: string }[];
    /** The event its last answer was for, and that answer. */
    answeredFor: number;
    answer: string | null;
  }
  let states = new WeakMap<Element, State>();
  let resend: (el: Element) => void = () => {};
  let owed: Element[] = [];
  let composing: Element | null = null;
  let applying = false;

  function stateOf(el: Element): State {
    let state = states.get(el);
    if (!state) {
      state = { sent: [], answeredFor: -1, answer: null };
      states.set(el, state);
    }
    return state;
  }

  /** Makes the edit as typing would, so it is a step in the browser's undo history. */
  function edit(el: TextField, change: Edit): void {
    const value = el.value;
    if (document.activeElement === el && !hasNoCaret(el) && typeof document.execCommand === "function") {
      const start = el.selectionStart ?? value.length;
      const end = el.selectionEnd ?? value.length;
      const direction = el.selectionDirection ?? undefined;
      el.setSelectionRange(change.from, change.to);
      applying = true;
      let done = false;
      try {
        done = document.execCommand(change.text === "" ? "delete" : "insertText", false, change.text);
      } catch {
        // It is deprecated, and may go; setting the value below only costs the undo history.
      } finally {
        applying = false;
      }
      if (!done || el.value !== applyEdit(value, change)) {
        el.value = applyEdit(value, change);
      }
      el.setSelectionRange(moved(start, change), moved(end, change), direction);
      return;
    }
    const start = el.selectionStart;
    const end = el.selectionEnd;
    el.value = applyEdit(value, change);
    if (document.activeElement === el && start !== null && end !== null) {
      el.setSelectionRange(moved(start, change), moved(end, change));
    }
  }

  return {
    /** True while this code is making an edit, whose input event is not the user's. */
    get applying(): boolean {
      return applying;
    },

    setResend(fn: (el: Element) => void): void {
      resend = fn;
    },

    listen(doc: Document): void {
      doc.addEventListener("compositionstart", (e) => {
        composing = e.target as Element;
      });
      doc.addEventListener("compositionend", () => {
        composing = null;
      });
    },

    /** An event carrying this value went out. */
    sent(el: Element, id: number, value: string): void {
      stateOf(el).sent.push({ id: id, value: value });
    },

    /** The server rendered answer for this field, having handled events up to handled. */
    reconcile(el: TextField, answer: string, handled: number): void {
      const state = stateOf(el);
      const mine = el.value;
      let seen: { id: number; value: string } | null = null;
      while (state.sent.length && state.sent[0].id <= handled) {
        seen = state.sent.shift()!;
      }
      const seenId = seen ? seen.id : state.answeredFor;
      // What it had seen: its own answer, if it already gave one for that event, or what that event said.
      const base = state.answer !== null && state.answeredFor === seenId ? state.answer
        : seen ? seen.value : state.answer ?? el.defaultValue;
      state.answeredFor = seenId;
      state.answer = answer;
      if (composing === el) {
        return; // The input event ending it brings a new answer.
      }
      const unseen = state.sent.length > 0;
      if (hasNoCaret(el)) {
        if (!unseen && mine === base && mine !== answer) {
          el.value = answer;
        }
        return;
      }
      el.defaultValue = answer; // What a form reset gives; if the user has not touched it, this is its value too.
      const change = merge(base, answer, mine);
      if (el.value !== mine) {
        el.value = applyEdit(mine, change);
      } else if (change) {
        edit(el, change);
      }
      // The server hears what it does not have, or the two stay apart.
      if (el.value !== answer && (change !== null || !unseen) && el.hasAttribute("data-j2-input")) {
        owed.push(el);
      }
    },

    /** Sends what the last patch left the server without. After the morph, as sending reads the field. */
    flush(): void {
      const fields = owed;
      owed = [];
      for (const el of fields) {
        resend(el);
      }
    },

    /** A new server-side tab: what went to the old one means nothing to it. */
    forget(): void {
      states = new WeakMap<Element, State>();
      owed = [];
    }
  };
}
