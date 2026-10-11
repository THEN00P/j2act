import { describe, expect, it } from "vitest";
import { backoff, errorText, named } from "../../main/ts/util.ts";

describe("backoff", () => {
  it("doubles up to 5 s, of which half is random", () => {
    expect(backoff(0, () => 0)).toBe(125);
    expect(backoff(0, () => 1)).toBe(250);
    expect(backoff(3, () => 0)).toBe(1000);
    expect(backoff(10, () => 0)).toBe(2500);
    expect(backoff(10, () => 1)).toBe(5000);
  });
});

describe("errorText", () => {
  it("reads name and message, or prints the value", () => {
    expect(errorText(named("AbortError", "gone"))).toBe("AbortError: gone");
    expect(errorText(new TypeError("bad"))).toBe("TypeError: bad");
    expect(errorText({ message: "plain" })).toBe("Error: plain");
    expect(errorText("text")).toBe("Error: text");
  });
});
