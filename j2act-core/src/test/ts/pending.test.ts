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

  it("swaps in the pending markup and blocks the element until the ack", () => {
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
