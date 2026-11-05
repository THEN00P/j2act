import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createClients } from "../../main/ts/clients.ts";
import { listenForEvents } from "../../main/ts/events.ts";
import { createFields } from "../../main/ts/fields.ts";
import { createPending, type Pending } from "../../main/ts/pending.ts";
import type { ClientMessage } from "../../main/ts/protocol.ts";
import { createUploads } from "../../main/ts/uploads.ts";

describe("Events", () => {
  let sent: ClientMessage[];
  let pending: Pending;
  const fields = createFields();

  beforeAll(() => {
    // Listeners stay on the document for the whole file; each test reads what it sent.
    const send = (m: ClientMessage) => sent.push(m);
    pending = createPending();
    const uploads = createUploads(send, () => "tok");
    const clients = createClients({ send: send, connected: () => true, uploads: uploads, morphSlot: () => {} });
    listenForEvents(document, { send: send, pending: pending, clients: clients, uploads: uploads, fields: fields });
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

  it("sends every click to its handler, in order, however fast they come", () => {
    document.body.innerHTML = '<button data-j2-click="h1">Go</button>';
    const button = document.querySelector("button")!;
    button.click();
    button.click();
    button.click();
    expect(sent).toEqual([
      { type: "event", id: "1", handler: "h1", value: "" },
      { type: "event", id: "2", handler: "h1", value: "" },
      { type: "event", id: "3", handler: "h1", value: "" }
    ]);
    expect(button.getAttribute("aria-busy")).toBe("true");
    pending.ack("1");
    pending.ack("2");
    expect(button.getAttribute("aria-busy")).toBe("true");
    pending.ack("3");
    expect(button.hasAttribute("aria-busy")).toBe(false);
  });

  it("blocks a form until the ack of its submit, so a double submit dies here", () => {
    document.body.innerHTML = '<form data-j2-submit="h6"><input name="q" value="a"></form>';
    const form = document.querySelector("form")!;
    const submit = () => form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));
    submit();
    submit();
    expect(sent).toHaveLength(1);
    pending.ack((sent[0] as { id: string }).id);
    submit();
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

  function blur(el: Element): void {
    el.dispatchEvent(new FocusEvent("focusout", { bubbles: true }));
  }

  function press(el: Element, key: string): void {
    el.dispatchEvent(new KeyboardEvent("keydown", { key: key, bubbles: true }));
  }

  function undo(el: HTMLInputElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "historyUndo" }));
  }

  const carried = (m: ClientMessage) => (m as { id: string }).id;

  it("takes the server's answer to the value a blur carried (ADR 0034)", () => {
    const el = input('<input data-j2-blur="h" data-j2-controlled="value" value="">');
    el.value = "  name  ";
    blur(el);
    expect(sent).toMatchObject([{ handler: "h", value: "  name  ", echo: true }]);
    fields.reconcile(el, "name", Number(carried(sent[0])));
    expect(el.value).toBe("name");
  });

  it("takes the server's answer to the value a key carried", () => {
    const el = input('<input data-j2-keydown="h" data-j2-keys="Enter" data-j2-controlled="value" value="">');
    el.value = "sent";
    press(el, "Enter");
    expect(sent).toMatchObject([{ handler: "h", value: "sent", echo: true }]);
    fields.reconcile(el, "", Number(carried(sent[0])));
    expect(el.value).toBe("");
  });

  it("sends an input still waiting before a blur or a key that comes after it", () => {
    vi.useFakeTimers();
    const el = input('<input data-j2-input="i" data-j2-blur="b" data-j2-keydown="k" data-j2-debounce="300">');
    type(el, "abc");
    press(el, "Enter");
    type(el, "abcd");
    blur(el);
    expect(sent).toMatchObject([
      { handler: "i", value: "abc" }, { handler: "k", value: "abc" },
      { handler: "i", value: "abcd" }, { handler: "b", value: "abcd" }
    ]);
    vi.advanceTimersByTime(1000);
    expect(sent.length).toBe(4);
  });

  it("sends a throttled input still waiting before a blur that comes after it", () => {
    vi.useFakeTimers();
    const el = input('<input data-j2-input="i" data-j2-blur="b" data-j2-throttle="300">');
    type(el, "a");
    type(el, "ab");
    blur(el);
    expect(sent).toMatchObject([{ handler: "i", value: "a" }, { handler: "i", value: "ab" }, { handler: "b", value: "ab" }]);
    vi.advanceTimersByTime(1000);
    expect(sent.length).toBe(3);
  });

  it("remembers nothing for a field the server does not control", () => {
    const remembered = vi.spyOn(fields, "sent");
    const el = input('<input data-j2-input="h">');
    type(el, "a");
    expect(sent).toMatchObject([{ handler: "h", value: "a" }]);
    expect(sent[0]).not.toHaveProperty("echo");
    expect(remembered).not.toHaveBeenCalled();
    remembered.mockRestore();
  });

  it("marks an event as an undo only when the undo is the latest change", () => {
    vi.useFakeTimers();
    const el = input('<input data-j2-input="h" data-j2-debounce="300">');
    undo(el, "a");
    type(el, "ab");
    vi.advanceTimersByTime(300);
    expect(sent).toEqual([{ type: "event", id: carried(sent[0]), handler: "h", value: "ab" }]);
    type(el, "abc");
    undo(el, "ab");
    vi.advanceTimersByTime(300);
    expect(sent[1]).toMatchObject({ value: "ab", undo: true });
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
