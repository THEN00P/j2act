import { describe, expect, it } from "vitest";
import { errorName, project, runWeb } from "../../main/ts/web.ts";

describe("runWeb", () => {
  const root = () => ({
    storage: {
      items: { a: "1" } as Record<string, string>,
      getItem(key: string) {
        return this.items[key] ?? null;
      },
      setItem(key: string, value: string) {
        this.items[key] = value;
      }
    },
    title: "t",
    later: () => Promise.resolve({ x: 1, y: 2, deep: { z: 3, w: 4 } })
  });

  it("follows the path, then calls", async () => {
    const r = root();
    await runWeb({ p: [["g", "storage"]], k: "call", n: "setItem", a: ["b", "2"] }, r);
    expect(await runWeb({ p: [["g", "storage"]], k: "call", n: "getItem", a: ["b"] }, r)).toBe("2");
  });

  it("gets and sets", async () => {
    const r = root();
    await runWeb({ p: [], k: "set", n: "title", a: ["new"] }, r);
    expect(await runWeb({ p: [], k: "get", n: "title", a: [] }, r)).toBe("new");
  });

  it("awaits a promise and copies only what the shape names", async () => {
    const value = await runWeb({ p: [], k: "call", n: "later", a: [], r: { x: 1, deep: { z: 1 } } }, root());
    expect(value).toEqual({ x: 1, deep: { z: 3 } });
  });

  it("throws synchronously when the path breaks, so the caller can reply at once", () => {
    expect(() => runWeb({ p: [["g", "missing"]], k: "get", n: "x", a: [] }, root())).toThrow("missing is undefined");
  });

  it("puts resolve and reject where the callbacks go", async () => {
    const r = {
      geo: {
        locate(ok: (v: unknown) => void, _fail: unknown, options: unknown) {
          ok({ options: options });
        },
        deny(_ok: unknown, fail: (e: unknown) => void) {
          fail({ code: 1, message: "denied" });
        }
      }
    };
    expect(await runWeb({ p: [["g", "geo"]], k: "call", n: "locate", a: [null, null, { high: true }], cb: [0, 1] }, r))
      .toEqual({ options: { high: true } });
    await expect(runWeb({ p: [["g", "geo"]], k: "call", n: "deny", a: [null, null], cb: [0, 1] }, r))
      .rejects.toMatchObject({ name: "NotAllowedError", message: "denied" });
  });

  it("constructs without returning the object", async () => {
    const made: unknown[] = [];
    const r = { Thing: class { constructor(a: unknown) { made.push(a); } } };
    expect(await runWeb({ p: [["g", "Thing"]], k: "new", a: [7] }, r)).toBeUndefined();
    expect(made).toEqual([7]);
  });
});

describe("project", () => {
  it("turns missing values into null", () => {
    expect(project(undefined, 1)).toBeNull();
    expect(project({ a: null }, { a: { b: 1 } })).toEqual({ a: null });
  });
});

describe("errorName", () => {
  it("names geolocation errors from their code", () => {
    expect(errorName({ code: 3 })).toBe("TimeoutError");
    expect(errorName({ code: 9 })).toBe("Error");
    expect(errorName(null)).toBe("Error");
  });
});
