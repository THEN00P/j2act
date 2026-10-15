import { beforeEach, describe, expect, it } from "vitest";
import { remember, takePrevious } from "../../main/ts/reopen.ts";

describe("reopen", () => {
  beforeEach(() => sessionStorage.clear());

  it("restores the closed page's tab when going back or forward, once", () => {
    remember("/orders?id=1", "old");
    expect(takePrevious("/orders?id=1", "fresh", "back_forward")).toEqual({ restore: "old" });
    expect(takePrevious("/orders?id=1", "fresh", "back_forward")).toEqual({});
  });

  it("forgets it on a reload, and leaves it alone on any other load, such as a duplicated tab", () => {
    remember("/", "old");
    expect(takePrevious("/", "fresh", "reload")).toEqual({ forget: "old" });
    remember("/", "old");
    expect(takePrevious("/", "fresh", "navigate")).toEqual({});
  });

  it("keys by URL, so Back across pages finds each page's own tab", () => {
    remember("/a", "tab-a");
    remember("/b", "tab-b");
    expect(takePrevious("/a", "fresh", "back_forward")).toEqual({ restore: "tab-a" });
  });

  it("moves a tab that navigated softly to its latest URL", () => {
    remember("/a", "same");
    remember("/b", "same");
    expect(takePrevious("/a", "fresh", "back_forward")).toEqual({});
    expect(takePrevious("/b", "fresh", "back_forward")).toEqual({ restore: "same" });
  });

  it("ignores its own token and keeps at most twenty URLs", () => {
    remember("/", "mine");
    expect(takePrevious("/", "mine", "back_forward")).toEqual({});
    for (let i = 0; i < 25; i++) {
      remember("/p" + i, "t" + i);
    }
    expect(takePrevious("/p0", "fresh", "back_forward")).toEqual({});
    expect(takePrevious("/p24", "fresh", "back_forward")).toEqual({ restore: "t24" });
  });
});
