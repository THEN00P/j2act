import { beforeEach, describe, expect, it, vi } from "vitest";
import { createClients, type Clients } from "../../main/ts/clients.ts";
import type { ClientMessage } from "../../main/ts/protocol.ts";
import { createUploads } from "../../main/ts/uploads.ts";

describe("Clients", () => {
  let sent: ClientMessage[];
  let morphed: [Element, string][];
  let clients: Clients;
  let up: boolean;

  beforeEach(() => {
    sent = [];
    up = true;
    morphed = [];
    const send = (m: ClientMessage) => sent.push(m);
    clients = createClients({ send: send, connected: () => up, uploads: createUploads(send, () => "tok"), morphSlot: (el, html) => morphed.push([el, html]) });
    document.body.innerHTML = "";
  });

  describe("results (ADR 0032)", () => {
    it("sends a result again after a reconnect until the server has it", () => {
      clients.reply("7", true, "value");
      expect(sent).toHaveLength(1);
      clients.resendResults();
      expect(sent).toHaveLength(2);
      expect(sent[1]).toEqual(sent[0]);
      clients.resultSeen("7");
      clients.resendResults();
      expect(sent).toHaveLength(2);
    });

    it("keeps a result back while the socket is down, and sends it once when it is up", () => {
      up = false;
      clients.reply("7", true, "value");
      expect(sent).toHaveLength(0);
      up = true;
      clients.resendResults();
      expect(sent).toHaveLength(1);
    });

    it("sends results no faster than the server's limit allows", () => {
      vi.useFakeTimers();
      try {
        up = false;
        for (let i = 0; i < 80; i++) {
          clients.reply(String(i), true, i);
        }
        up = true;
        clients.resendResults();
        expect(sent).toHaveLength(20);
        vi.advanceTimersByTime(1000);
        expect(sent).toHaveLength(40);
      } finally {
        vi.useRealTimers();
      }
    });

    it("gets to every result in turn, though the first keep coming due again", () => {
      vi.useFakeTimers();
      try {
        up = false;
        for (let i = 0; i < 80; i++) {
          clients.reply(String(i), true, i);
        }
        up = true;
        clients.resendResults();
        // No acknowledgement comes, as on a half-broken connection, so the first are due again every 2 seconds.
        vi.advanceTimersByTime(10000);
        const ids = new Set(sent.map((m) => (m as { id: string }).id));
        expect(ids.size).toBe(80);
      } finally {
        vi.useRealTimers();
      }
    });

    it("sends a result the server gave no answer to again while the socket stays up", () => {
      vi.useFakeTimers();
      try {
        clients.reply("7", true, "value");
        expect(sent).toHaveLength(1);
        vi.advanceTimersByTime(1000);
        expect(sent).toHaveLength(1);
        vi.advanceTimersByTime(1000);
        expect(sent).toHaveLength(2);
        clients.resultSeen("7");
        vi.advanceTimersByTime(10000);
        expect(sent).toHaveLength(2);
      } finally {
        vi.useRealTimers();
      }
    });

    it("fails a call whose result is past the socket's limit, which would close every socket it went on", () => {
      up = false;
      clients.reply("7", true, "x".repeat(1000));
      up = true;
      clients.resendResults(100);
      expect(sent).toHaveLength(1);
      expect(sent[0]).toMatchObject({ type: "callResult", id: "7", ok: false });
      expect(JSON.stringify(sent[0])).toContain("RangeError");
      expect(JSON.stringify(sent[0]).length).toBeLessThan(200);
    });

    it("drops the answer of a call from before a new server-side tab, whose ids start over", () => {
      const answer = clients.replier("7");
      clients.forgetResults();
      answer(true, "from the old tab");
      clients.resendResults();
      expect(sent).toHaveLength(0);
    });

    it("does not answer a call still waiting for its module once a new server-side tab has come", () => {
      document.body.innerHTML = '<div data-j2-client="c1" data-j2-module="data:text/javascript,await new Promise(() => {})" data-j2-export="x"></div>';
      clients.scan();
      clients.call({ type: "clientCall", id: "7", client: "c1", action: "read", args: "[]" });
      clients.forgetResults();
      document.body.innerHTML = "";
      clients.scan();
      clients.resendResults();
      expect(sent).toHaveLength(0);
    });

    it("forgets results a new server-side tab never asked for", () => {
      clients.reply("7", true, "value");
      clients.forgetResults();
      clients.resendResults();
      expect(sent).toHaveLength(1);
    });
  });

  describe("revive", () => {
    it("turns callbacks into functions that call Java", () => {
      const props = clients.revive('{"onPick":{"$fn":"h9"}}') as { onPick: (...args: unknown[]) => void };
      props.onPick(1, "two");
      expect(sent).toEqual([{ type: "callback", handler: "h9", args: '[1,"two"]' }]);
    });

    it("finds slots and makes snapshots", () => {
      document.body.innerHTML = '<div data-j2-slot="s1">live</div>';
      const props = clients.revive('{"slot":{"$slot":"s1"},"snap":{"$html":"<b>once</b>"}}') as Record<string, HTMLElement>;
      expect(props.slot).toBe(document.querySelector("[data-j2-slot]"));
      expect(props.snap.innerHTML).toBe("<b>once</b>");
      expect(props.snap.style.display).toBe("contents");
    });

    it("sends an UploadTarget's blob through a callback", () => {
      const props = clients.revive('{"to":{"$upload":"h10"}}') as { to: { send(b: Blob, n?: string): Promise<void> } };
      props.to.send(new Blob(["abc"], { type: "text/plain" }), "a.txt");
      expect(sent).toEqual([{ type: "callback", handler: "h10", args: "[]", fileId: "1", fileName: "a.txt", fileSize: 3,
        fileType: "text/plain" }]);
    });

    it("tracks live content, and reports it for as long as patches come for it", () => {
      const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
      const root = clients.revive('{"$live":"<div data-j2-scope=\\"a7\\">x</div>"}') as Element;
      expect(root.getAttribute("data-j2-scope")).toBe("a7");
      // Each is no damage to the page, or it would be repaired: patches buffered in a drop can follow, and a report can be lost.
      expect(clients.anchorGone("a7")).toBe(true);
      expect(clients.anchorGone("a7")).toBe(true);
      expect(sent).toEqual([{ type: "liveGone", anchor: "a7" }, { type: "liveGone", anchor: "a7" }]);
      expect(warn).toHaveBeenCalledTimes(1);
      expect(clients.anchorGone("a8")).toBe(false);
      warn.mockRestore();
    });
  });

  it("answers a call for a client that is not mounted", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    clients.call({ type: "clientCall", id: "5", client: "j2c-x", action: "play", args: "[]" });
    expect(sent).toEqual([{ type: "callResult", id: "5", ok: false, error: "AbortError: the client is not mounted" }]);
    warn.mockRestore();
  });

  it("replies with JSON, and a value JSON cannot hold as an error", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    clients.reply("1", true, undefined);
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    clients.reply("2", true, cyclic);
    expect(sent[0]).toEqual({ type: "callResult", id: "1", ok: true, value: "null" });
    expect(sent[1]).toMatchObject({ type: "callResult", id: "2", ok: false, error: expect.stringMatching(/^TypeError: /) });
    warn.mockRestore();
  });

  it("syncs server attributes, keeps the client's own, and morphs slots where they live", () => {
    document.body.innerHTML = '<div id="c" data-j2-client="j2c-1" class="a" title="t"></div>'
      + '<div data-j2-slot="s2">moved here</div>';
    const el = document.getElementById("c")!;
    clients.sync(el, fresh('<div id="c" data-j2-client="j2c-1" class="a" title="t"></div>'));
    el.setAttribute("data-client-own", "1");

    clients.sync(el, fresh('<div id="c" data-j2-client="j2c-1" class="b"><div data-j2-slot="s2"><i>new</i></div></div>'));
    expect(el.getAttribute("class")).toBe("b");
    expect(el.hasAttribute("title")).toBe(false);
    expect(el.getAttribute("data-client-own")).toBe("1");
    expect(morphed).toEqual([[document.querySelector('[data-j2-slot="s2"]'), "<i>new</i>"]]);
  });

  it("reports a slot that left the document once", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    document.body.innerHTML = '<div id="c" data-j2-client="j2c-1"></div>';
    const el = document.getElementById("c")!;
    const next = fresh('<div id="c" data-j2-client="j2c-1"><div data-j2-slot="gone"></div></div>');
    clients.sync(el, next);
    clients.sync(el, next);
    expect(sent).toEqual([{ type: "clientError", client: "j2c-1", error: "slot gone left the document, so it stopped updating" }]);
    warn.mockRestore();
  });

  function fresh(html: string): Element {
    const template = document.createElement("template");
    template.innerHTML = html;
    return template.content.firstElementChild!;
  }
});
