import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRepair } from "../../main/ts/repair.ts";
import type { ClientMessage } from "../../main/ts/protocol.ts";

describe("repair (ADR 0032)", () => {
  let sent: ClientMessage[];

  beforeEach(() => {
    sent = [];
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("asks again until a whole page arrives, since a rate limit refuses without an answer", () => {
    const repair = createRepair((m) => sent.push(m));
    repair.ask();
    expect(sent).toEqual([{ type: "resync" }]);
    vi.advanceTimersByTime(2000);
    expect(sent).toHaveLength(2);
    repair.arrived();
    vi.advanceTimersByTime(10000);
    expect(sent).toHaveLength(2);
  });

  it("asks once however many patches find nothing", () => {
    const repair = createRepair((m) => sent.push(m));
    repair.ask();
    repair.ask();
    repair.ask();
    expect(sent).toHaveLength(1);
    repair.arrived();
  });
});
