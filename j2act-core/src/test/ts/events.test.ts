import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createClients } from "../../main/ts/clients.ts";
import { listenForEvents } from "../../main/ts/events.ts";
import { createPending, type Pending } from "../../main/ts/pending.ts";
import type { ClientMessage } from "../../main/ts/protocol.ts";
import { createUploads } from "../../main/ts/uploads.ts";

describe("Events", () => {
  let sent: ClientMessage[];
  let pending: Pending;

  beforeAll(() => {
    // Listeners stay on the document for the whole file; each test reads what it sent.
    const send = (m: ClientMessage) => sent.push(m);
    pending = createPending();
    const uploads = createUploads(send, () => "tok");
    const clients = createClients({ send: send, uploads: uploads, morphSlot: () => {} });
    listenForEvents(document, { send: send, pending: pending, clients: clients, uploads: uploads });
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
    expect(sent).toEqual([{ type: "event", id: expect.any(String), handler: "h1", value: "" }]);
    expect(button.getAttribute("aria-busy")).toBe("true");
    pending.ack((sent[0] as { id: string }).id);
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
    expect(sent).toEqual([expect.objectContaining({ handler: "h2", value: "ab" })]);
  });

  it("throttles: the leading value at once, the latest when the interval ends", async () => {
    vi.useFakeTimers();
    const el = input('<input data-j2-input="h3" data-j2-throttle="100">');
    type(el, "a");
    type(el, "ab");
    type(el, "abc");
    expect(sent.map((m) => (m as { value: string }).value)).toEqual(["a"]);
    await vi.advanceTimersByTimeAsync(100);
    expect(sent.map((m) => (m as { value: string }).value)).toEqual(["a", "abc"]);
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
    expect(sent).toEqual([expect.objectContaining({ handler: "h5", key: "Enter", ctrlKey: true, shiftKey: true, altKey: false, metaKey: false })]);
  });

  it("submits fields URL-encoded with the submitter, never the browser's way", () => {
    document.body.innerHTML = '<form data-j2-submit="h6"><input name="q" value="a b&c">'
      + '<button name="go" value="1">Go</button></form>';
    const form = document.querySelector("form")!;
    const event = new SubmitEvent("submit", { bubbles: true, cancelable: true, submitter: form.querySelector("button") });
    form.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(sent).toEqual([expect.objectContaining({ handler: "h6", value: "q=a%20b%26c&go=1" })]);
  });

  it("runs a bound window() call inside the click and sends its result", async () => {
    document.body.innerHTML = '<button data-j2-click="h7">Go</button>';
    const button = document.querySelector("button")!;
    button.setAttribute("data-j2-call-click", JSON.stringify({ window: { path: [], kind: "get", name: "innerWidth", args: [] } }));
    button.click();
    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toMatchObject({ handler: "h7", value: String(window.innerWidth) });
  });

  it("sends a bound action's failure as its error", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    document.body.innerHTML = '<button data-j2-click="h8">Go</button>';
    const button = document.querySelector("button")!;
    button.setAttribute("data-j2-call-click", JSON.stringify({ client: "j2c-none", action: "act", args: [] }));
    button.click();
    expect(sent).toEqual([expect.objectContaining({ handler: "h8", value: "", error: "InvalidStateError: the client has not mounted yet" })]);
    warn.mockRestore();
  });
});
