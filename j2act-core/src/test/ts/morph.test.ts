import { beforeEach, describe, expect, it } from "vitest";
import { createMorpher } from "../../main/ts/morph.ts";
import { createPending } from "../../main/ts/pending.ts";

describe("morph and focus (ADR 0013)", () => {
  const morpher = () => createMorpher(createPending(), () => {});

  function page(html: string): HTMLElement {
    document.body.innerHTML = '<div data-j2-scope="s1">' + html + "</div>";
    return document.body.firstElementChild as HTMLElement;
  }

  const patched = (html: string) => '<div data-j2-scope="s1">' + html + "</div>";

  beforeEach(() => {
    document.body.innerHTML = "";
  });

  it("updates the label of a focused button, which a real click leaves focused", () => {
    const root = page("<button>Count: 0</button>");
    const button = root.querySelector("button")!;
    button.focus();
    expect(document.activeElement).toBe(button);
    morpher().patch("s1", patched("<button>Count: 1</button>"), false);
    expect(root.querySelector("button")).toBe(button);
    expect(button.textContent).toBe("Count: 1");
    expect(document.activeElement).toBe(button);
  });

  it("updates a focused link's text", () => {
    const root = page('<a href="#x">Open</a>');
    root.querySelector("a")!.focus();
    morpher().patch("s1", patched('<a href="#x">Close</a>'), false);
    expect(root.querySelector("a")!.textContent).toBe("Close");
  });

  it("leaves what is being typed in a focused input, and updates the rest of it", () => {
    const root = page('<input class="a" value="server">');
    const input = root.querySelector("input")!;
    input.focus();
    input.value = "typed";
    morpher().patch("s1", patched('<input class="b" value="other">'), false);
    expect(input.value).toBe("typed");
    expect(input.className).toBe("b");
  });

  it("does update an input that is not focused", () => {
    const root = page('<input value="server">');
    morpher().patch("s1", patched('<input value="other">'), false);
    expect(root.querySelector("input")!.value).toBe("other");
  });

  it("leaves what is being typed in a focused text area", () => {
    const root = page('<textarea class="a">server</textarea>');
    const area = root.querySelector("textarea")!;
    area.focus();
    area.value = "typed";
    morpher().patch("s1", patched('<textarea class="b">other</textarea>'), false);
    expect(area.value).toBe("typed");
    expect(area.className).toBe("b");
  });

  it("leaves the contents of a focused editable element", () => {
    const root = page('<div class="a" contenteditable="true" tabindex="0">typed</div>');
    const editable = root.querySelector<HTMLElement>("[contenteditable]")!;
    editable.focus();
    morpher().patch("s1", patched('<div class="b" contenteditable="true" tabindex="0">other</div>'), false);
    expect(editable.textContent).toBe("typed");
    expect(editable.className).toBe("b");
  });
});
