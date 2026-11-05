import { beforeEach, describe, expect, it, vi } from "vitest";
import { applyEdit, createFields, merge, type Fields } from "../../main/ts/fields.ts";

describe("merge (ADR 0034)", () => {
  const cases: [string, string, string, string, string][] = [
    ["nobody typed since", "hi :smile:", "hi 😄", "hi :smile:", "hi 😄"],
    ["still typing after it", "hi :smile:", "hi 😄", "hi :smile: and mo", "hi 😄 and mo"],
    ["typed right up against it", "hi :smile:", "hi 😄", "hi :smile:x", "hi 😄x"],
    ["typed before it", "b :smile:", "b 😄", "ab :smile:", "ab 😄"],
    ["editing inside what it rewrites", "hi :smile:", "hi 😄", "hi :smi-le:", "hi :smi-le:"],
    ["Enter clears, nothing typed since", "hello", "", "hello", ""],
    ["Enter clears, already typing the next one", "hello", "", "hellonext", "next"],
    ["digits only: the server has not seen the letter", "12", "12", "12a", "12a"],
    ["digits only: rejects it after seeing it", "12a", "12", "12a", "12"],
    ["an echo of what was typed", "abc", "abc", "abcd", "abcd"],
    ["the edit is already here", "hi :smile:", "hi 😄", "hi 😄", "hi 😄"],
    ["already merged, more typed", "hi :smile: h", "hi 😄 h", "hi 😄 he", "hi 😄 he"],
    ["the server inserts at the start, the user typed at the end", "text", "IMG text", "text and", "IMG text and"],
    ["both insert at the same spot: the user's stays", "ab", "abX", "abY", "abY"],
    ["the server changes what it saw, the user added to it", "abc", "ABC", "abcd", "ABCd"],
    ["two rewrites around the user's edit wait", ":a: x :b:", "A x B", ":a: xy :b:", ":a: xy :b:"]
  ];
  for (const [name, base, server, mine, want] of cases) {
    it(name, () => {
      expect(applyEdit(mine, merge(base, server, mine))).toBe(want);
    });
  }
});

describe("random typing against a slow server (ADR 0034)", () => {
  /** A server that rewrites :) and may insert once on its own; messages keep their order on one socket. */
  function session(seed: number, delay: number, anywhere: boolean) {
    let r = (seed * 2654435761) % 4294967296;
    const rnd = (n: number) => {
      r = (r * 1664525 + 1013904223) % 4294967296;
      return Math.floor((r / 4294967296) * n);
    };
    document.body.innerHTML = '<input data-j2-input="h" data-j2-controlled="value" value="">';
    const el = document.querySelector("input")!;
    let id = 0;
    const toServer: { at: number; id: number; value: string }[] = [];
    const toClient: { at: number; handled: number; value: string }[] = [];
    let now = 0;
    const later = <T extends { at: number }>(q: T[], m: Omit<T, "at">) => {
      q.push({ ...m, at: Math.max(now + 1 + rnd(delay), q.length ? q[q.length - 1].at : 0) } as T);
    };
    const fields = createFields();
    const send = () => {
      id++;
      fields.sent(el, id, el.value);
      later(toServer, { id: id, value: el.value });
    };
    fields.setResend(send);
    let serverValue = "";
    let handled = 0;
    const typed: string[] = [];
    let letter = 0;
    let inserted = false;
    const steps = 40 + rnd(120);
    for (let step = 0; step < steps || toServer.length || toClient.length; step++) {
      now++;
      if (step < steps && rnd(3) === 0) {
        const at = anywhere && rnd(4) === 0 ? rnd(el.value.length + 1) : el.value.length;
        const text = rnd(8) === 0 ? ":)" : String.fromCharCode(0x4e00 + letter++);
        if (text !== ":)") {
          typed.push(text);
        }
        el.value = el.value.slice(0, at) + text + el.value.slice(at);
        send();
      }
      while (toServer.length && toServer[0].at <= now) {
        const m = toServer.shift()!;
        serverValue = m.value.split(":)").join("☺");
        handled = m.id;
        later(toClient, { handled: handled, value: serverValue });
      }
      if (step < steps && !inserted && rnd(25) === 0) {
        inserted = true;
        serverValue = "[i]" + serverValue;
        later(toClient, { handled: handled, value: serverValue });
      }
      while (toClient.length && toClient[0].at <= now) {
        const m = toClient.shift()!;
        fields.reconcile(el, m.value, m.handled);
        fields.flush();
      }
    }
    return { lost: typed.filter((t) => !el.value.includes(t)), field: el.value, server: serverValue };
  }

  for (const [name, delay, anywhere] of [["slow link", 40, false], ["fast link", 6, false], ["typing anywhere", 40, true]] as const) {
    it(`loses no typed letter, and both sides agree: ${name}`, () => {
      for (let seed = 1; seed <= 400; seed++) {
        const out = session(seed, delay, anywhere);
        expect(out.lost, `seed ${seed}`).toEqual([]);
        expect(out.field, `seed ${seed}`).toBe(out.server);
      }
    });
  }
});

describe("Fields (ADR 0034)", () => {
  let fields: Fields;
  let resent: Element[];

  function field(html: string): HTMLInputElement {
    document.body.innerHTML = html;
    return document.body.firstElementChild as HTMLInputElement;
  }

  beforeEach(() => {
    fields = createFields();
    resent = [];
    fields.setResend((el) => resent.push(el));
  });

  it("merges against what the server had seen when it rendered, not what it was last sent", () => {
    const el = field('<input data-j2-input="h" value="">');
    el.value = "a";
    fields.sent(el, 1, "a");
    el.value = "ab";
    fields.sent(el, 2, "ab");
    // It answers event 1 by rewriting it, not knowing of the b yet.
    fields.reconcile(el, "A", 1);
    expect(el.value).toBe("Ab");
  });

  it("answers only for the event it says: an older render takes nothing from the field", () => {
    const el = field('<input data-j2-input="h" value="">');
    el.value = "ab";
    fields.sent(el, 7, "ab");
    fields.reconcile(el, "", 3);
    expect(el.value).toBe("ab");
  });

  it("tells the server what was merged, and nothing when the two already agree", () => {
    const el = field('<input data-j2-input="h" value="">');
    el.value = "a";
    fields.sent(el, 1, "a");
    fields.reconcile(el, "a", 1);
    fields.flush();
    expect(resent).toEqual([]);
    el.value = "ab";
    fields.sent(el, 2, "ab");
    fields.reconcile(el, "[i]a", 1);
    fields.flush();
    expect(el.value).toBe("[i]ab");
    expect(resent).toEqual([el]);
  });

  it("does not hurry a field bound only to change: the server hears when the user is done", () => {
    const el = field('<input data-j2-change="h" value="a">');
    el.value = "ab";
    fields.reconcile(el, "[i]a", 0);
    fields.flush();
    expect(el.value).toBe("[i]ab");
    expect(resent).toEqual([]);
  });

  it("keeps the caret where the user was", () => {
    const el = field('<input data-j2-input="h" value="">');
    document.body.appendChild(el);
    el.focus();
    el.value = "hi :smile: there";
    el.setSelectionRange(16, 16);
    fields.sent(el, 1, "hi :smile:");
    fields.reconcile(el, "hi X", 1);
    expect(el.value).toBe("hi X there");
    expect(el.selectionStart).toBe(10);
  });

  it("applies nothing during IME composition", () => {
    const el = field('<input data-j2-input="h" value="">');
    document.body.appendChild(el);
    fields.listen(document);
    el.value = "a";
    fields.sent(el, 1, "a");
    el.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true }));
    fields.reconcile(el, "A", 1);
    expect(el.value).toBe("a");
    el.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true }));
    // The input event ending it goes out, and brings a new answer.
    fields.sent(el, 2, "a");
    fields.reconcile(el, "A", 2);
    expect(el.value).toBe("A");
  });

  it("falls back to setting the value where execCommand is missing or refuses", () => {
    const el = field('<input data-j2-input="h" value="">');
    document.body.appendChild(el);
    el.focus();
    el.value = "ab";
    fields.sent(el, 1, "ab");
    const exec = vi.fn(() => false);
    (document as unknown as { execCommand: unknown }).execCommand = exec;
    fields.reconcile(el, "AB", 1);
    expect(exec).toHaveBeenCalled();
    expect(el.value).toBe("AB");
    delete (document as unknown as { execCommand?: unknown }).execCommand;
  });

  it("gives a field with no caret the server's value only once the server has seen everything", () => {
    const el = field('<input type="number" data-j2-input="h" value="1">');
    el.value = "12";
    fields.sent(el, 1, "12");
    el.value = "123";
    fields.sent(el, 2, "123");
    fields.reconcile(el, "9", 1);
    expect(el.value).toBe("123");
    fields.reconcile(el, "9", 2);
    expect(el.value).toBe("9");
  });

  it("forgets what it sent for a new server-side tab", () => {
    const el = field('<input data-j2-input="h" value="x">');
    el.value = "xy";
    fields.sent(el, 5, "xy");
    fields.forget();
    fields.reconcile(el, "x", 1);
    expect(el.value).toBe("xy");
  });
});
