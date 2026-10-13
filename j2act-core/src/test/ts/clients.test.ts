import { beforeEach, describe, expect, it, vi } from "vitest";
import { createClients, type Clients } from "../../main/ts/clients.ts";
import type { ClientMessage } from "../../main/ts/protocol.ts";
import { createUploads } from "../../main/ts/uploads.ts";

describe("Clients", () => {
  let sent: ClientMessage[];
  let morphed: [Element, string][];
  let clients: Clients;

  beforeEach(() => {
    sent = [];
    morphed = [];
    const send = (m: ClientMessage) => sent.push(m);
    clients = createClients({ send: send, uploads: createUploads(send, () => "tok"), morphSlot: (el, html) => morphed.push([el, html]) });
    document.body.innerHTML = "";
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

    it("tracks live content, and reports it once it left the document", () => {
      const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
      const root = clients.revive('{"$live":"<div data-j2-scope=\\"a7\\">x</div>"}') as Element;
      expect(root.getAttribute("data-j2-scope")).toBe("a7");
      clients.anchorGone("a7");
      clients.anchorGone("a7");
      expect(sent).toEqual([{ type: "liveGone", anchor: "a7" }]);
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
