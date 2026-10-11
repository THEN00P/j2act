import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { Clients } from "../../main/ts/clients.ts";
import { Events } from "../../main/ts/events.ts";
import { Pending } from "../../main/ts/pending.ts";
import type { ClientMessage } from "../../main/ts/protocol.ts";
import { Uploads } from "../../main/ts/uploads.ts";

describe("Events", () => {
  let sent: ClientMessage[];
  let pending: Pending;

  beforeAll(() => {
    // Listeners stay on the document for the whole file; each test reads what it sent.
    const send = (m: ClientMessage) => sent.push(m);
    pending = new Pending();
    const uploads = new Uploads(send, () => "tok");
    const clients = new Clients({ send: send, uploads: uploads, morphSlot: () => {} });
    new Events({ send: send, pending: pending, clients: clients, uploads: uploads }).listen(document);
  });

  beforeEach(() => {
    sent = [];
    pending.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function input(html: string): HTMLInputElement {
    document.body.innerHTML = html;
    return document.querySelector("input")!;
  }

  function type(el: HTMLInputElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }

  it("sends a click to its handler and blocks the element until the ack", () => {
    document.body.innerHTML = '<button data-j2-click="h1">Go</button>';
    const button = document.querySelector("button")!;
    button.click();
    button.click();
    expect(sent).toEqual([{ t: "ev", h: "h1", v: "", a: expect.any(String) }]);
    expect(button.getAttribute("aria-busy")).toBe("true");
    pending.ack((sent[0] as { a: string }).a);
    button.click();
    expect(sent).toHaveLength(2);
  });

  it("debounces input to the latest value", async () => {
    vi.useFakeTimers();
    const el = input('<input data-j2-input="h2" data-j2-debounce="300">');
    type(el, "a");
    type(el, "ab");
    await vi.advanceTimersByTimeAsync(299);
    expect(sent).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(sent).toEqual([expect.objectContaining({ h: "h2", v: "ab" })]);
  });

  it("throttles: the leading value at once, the latest when the interval ends", async () => {
    vi.useFakeTimers();
    const el = input('<input data-j2-input="h3" data-j2-throttle="100">');
    type(el, "a");
    type(el, "ab");
    type(el, "abc");
    expect(sent.map((m) => (m as { v: string }).v)).toEqual(["a"]);
    await vi.advanceTimersByTimeAsync(100);
    expect(sent.map((m) => (m as { v: string }).v)).toEqual(["a", "abc"]);
    await vi.advanceTimersByTimeAsync(100);
    expect(sent).toHaveLength(2);
  });

  it("drops clicks inside a throttle interval", async () => {
    vi.useFakeTimers();
    document.body.innerHTML = '<span data-j2-click="h4" data-j2-throttle="100">x</span>';
    const span = document.querySelector("span")!;
    span.click();
    pending.clear();
    span.click();
    await vi.advanceTimersByTimeAsync(100);
    expect(sent).toHaveLength(1);
  });

  it("sends only the keys the server asked for, with modifiers", () => {
    const el = input('<input data-j2-keydown="h5" data-j2-keys="Enter Escape">');
    el.dispatchEvent(new KeyboardEvent("keydown", { key: "a", bubbles: true }));
    el.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", ctrlKey: true, shiftKey: true, bubbles: true }));
    expect(sent).toEqual([expect.objectContaining({ h: "h5", k: "Enter", m: "cs" })]);
  });

  it("submits fields URL-encoded with the submitter, never the browser's way", () => {
    document.body.innerHTML = '<form data-j2-submit="h6"><input name="q" value="a b&c">'
      + '<button name="go" value="1">Go</button></form>';
    const form = document.querySelector("form")!;
    const event = new SubmitEvent("submit", { bubbles: true, cancelable: true, submitter: form.querySelector("button") });
    form.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(sent).toEqual([expect.objectContaining({ h: "h6", v: "q=a%20b%26c&go=1" })]);
  });

  it("runs a bound window() call inside the click and sends its result", async () => {
    document.body.innerHTML = '<button data-j2-click="h7">Go</button>';
    const button = document.querySelector("button")!;
    button.setAttribute("data-j2-call-click", JSON.stringify({ w: { p: [], k: "get", n: "innerWidth", a: [] } }));
    button.click();
    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toMatchObject({ h: "h7", v: String(window.innerWidth) });
  });

  it("sends a bound action's failure as x", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    document.body.innerHTML = '<button data-j2-click="h8">Go</button>';
    const button = document.querySelector("button")!;
    button.setAttribute("data-j2-call-click", JSON.stringify({ c: "j2c-none", n: "act", a: [] }));
    button.click();
    expect(sent).toEqual([expect.objectContaining({ h: "h8", v: "", x: "InvalidStateError: the client has not mounted yet" })]);
    warn.mockRestore();
  });
});
