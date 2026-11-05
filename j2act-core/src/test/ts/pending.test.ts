import { beforeEach, describe, expect, it } from "vitest";
import { createPending, type Pending } from "../../main/ts/pending.ts";

describe("Pending", () => {
  let pending: Pending;
  let button: HTMLButtonElement;

  beforeEach(() => {
    pending = createPending();
    document.body.innerHTML = '<button>Save<template data-j2-pending>Saving…</template></button>';
    button = document.querySelector("button")!;
  });

  it("swaps in the pending markup until the ack", () => {
    pending.start(button, "1", button);
    expect(pending.has(button)).toBe(true);
    expect(button.getAttribute("aria-busy")).toBe("true");
    expect(button.hasAttribute("data-pending")).toBe(true);
    expect(button.querySelector("[data-j2-pending-live]")!.textContent).toBe("Saving…");

    pending.ack("1");
    expect(pending.has(button)).toBe(false);
    expect(button.hasAttribute("aria-busy")).toBe(false);
    expect(button.innerHTML).toBe("Save<template data-j2-pending=\"\">Saving…</template>");
  });

  it("stays pending until the last of several events is answered, and swaps its markup once", () => {
    const before = button.innerHTML;
    pending.start(button, "1", button);
    pending.start(button, "2", button);
    expect(button.querySelectorAll("[data-j2-pending-live]")).toHaveLength(1);
    pending.ack("1");
    expect(pending.has(button)).toBe(true);
    expect(button.hasAttribute("data-pending")).toBe(true);
    pending.ack("2");
    expect(pending.has(button)).toBe(false);
    expect(button.hasAttribute("data-pending")).toBe(false);
    expect(button.innerHTML).toBe(before);
  });

  it("only blocks what asks to be blocked", () => {
    pending.start(button, "1", button);
    expect(pending.blocks(button)).toBe(false);
    pending.start(button, "2", button, true);
    expect(pending.blocks(button)).toBe(true);
    pending.ack("2");
    expect(pending.blocks(button)).toBe(false);
    pending.ack("1");
  });

  it("leaves markup a morph already replaced", () => {
    pending.start(button, "1", button);
    button.innerHTML = "Saved";
    pending.ack("1");
    expect(button.innerHTML).toBe("Saved");
  });

  it("ignores an ack it does not know", () => {
    pending.ack("9");
    expect(pending.size).toBe(0);
  });

  it("forgets everything for a new server-side tab", () => {
    pending.start(button, "1", button);
    pending.clear();
    expect(pending.has(button)).toBe(false);
    expect(pending.size).toBe(0);
  });
});
