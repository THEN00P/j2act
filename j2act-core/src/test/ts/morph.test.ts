import { beforeEach, describe, expect, it } from "vitest";
import { createFields } from "../../main/ts/fields.ts";
import { createMorpher } from "../../main/ts/morph.ts";
import { createPending } from "../../main/ts/pending.ts";

describe("morph and focus (ADR 0013)", () => {
  const morpher = () => createMorpher(createPending(), () => {}, createFields());

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

  it("updates a focused button that is the whole component the patch is for", () => {
    document.body.innerHTML = '<div><button data-j2-scope="s1">Count: 0</button></div>';
    const button = document.querySelector("button")!;
    button.focus();
    morpher().patch("s1", '<button data-j2-scope="s1">Count: 1</button>', false);
    expect(document.querySelector("button")!.textContent).toBe("Count: 1");
    expect(document.activeElement).toBe(document.querySelector("button"));
  });

  it("updates a focused link's text", () => {
    const root = page('<a href="#x">Open</a>');
    root.querySelector("a")!.focus();
    morpher().patch("s1", patched('<a href="#x">Close</a>'), false);
    expect(root.querySelector("a")!.textContent).toBe("Close");
  });

  it("leaves what is being typed in a focused input, and updates the rest of it", () => {
    const root = page('<input class="a" value="server" data-j2-controlled="value">');
    const input = root.querySelector("input")!;
    input.focus();
    input.value = "typed";
    morpher().patch("s1", patched('<input class="b" value="other" data-j2-controlled="value">'), false);
    expect(input.value).toBe("typed");
    expect(input.className).toBe("b");
  });

  it("does update an input that is not focused", () => {
    const root = page('<input value="server" data-j2-controlled="value">');
    morpher().patch("s1", patched('<input value="other" data-j2-controlled="value">'), false);
    expect(root.querySelector("input")!.value).toBe("other");
  });

  it("leaves what is being typed in a focused text area", () => {
    const root = page('<textarea class="a" data-j2-controlled="value">server</textarea>');
    const area = root.querySelector("textarea")!;
    area.focus();
    area.value = "typed";
    morpher().patch("s1", patched('<textarea class="b" data-j2-controlled="value">other</textarea>'), false);
    expect(area.value).toBe("typed");
    expect(area.className).toBe("b");
  });

  it("clears a focused field the server cleared, once it has seen everything typed", () => {
    const root = page('<input value="" data-j2-controlled="value">');
    const input = root.querySelector("input")!;
    input.focus();
    input.value = "hello";
    const fields = createFields();
    fields.sent(input, 4, "hello");
    createMorpher(createPending(), () => {}, fields)
      .patch("s1", patched('<input value="" data-j2-controlled="value">'), false, 4);
    expect(input.value).toBe("");
  });

  it("leaves a field the render did not set, as it is the user's (ADR 0013)", () => {
    const root = page('<input value="" data-j2-controlled="">');
    const input = root.querySelector("input")!;
    input.value = "typed";
    morpher().patch("s1", patched('<input class="b" data-j2-controlled="">'), false, 9);
    expect(input.value).toBe("typed");
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

describe("stubs for what the page has (ADR 0036)", () => {
  const morpher = () => createMorpher(createPending(), () => {}, createFields());

  beforeEach(() => {
    document.body.innerHTML = "";
  });

  it("keeps the child a stub stands for, node and all, and patches around it", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p>0</p><section data-j2-scope="s2"><b>big</b></section></div>';
    const child = document.querySelector("section")!;
    // What the browser did to it since stays.
    child.setAttribute("data-mine", "yes");
    expect(morpher().patch("s1", '<div data-j2-scope="s1"><p>1</p><!--j2=s2--></div>', false)).toBe(true);
    expect(document.querySelector("p")!.textContent).toBe("1");
    expect(document.querySelector("section")).toBe(child);
    expect(child.getAttribute("data-mine")).toBe("yes");
    expect(document.body.innerHTML).not.toContain("j2=");
  });

  it("keeps stubbed rows inside a table, where an element would be moved out", () => {
    document.body.innerHTML = '<table><tbody data-j2-scope="s1"><tr data-j2-scope="s2"><td>a</td></tr>'
      + '<tr data-j2-scope="s3"><td>b</td></tr></tbody></table>';
    const first = document.querySelector('[data-j2-scope="s2"]')!;
    const second = document.querySelector('[data-j2-scope="s3"]')!;
    morpher().patch("s1", '<tbody data-j2-scope="s1"><!--j2=s2--><tr data-j2-scope="s3"><td>c</td></tr>'
      + '<tr data-j2-scope="s4"><td>d</td></tr></tbody>', false);
    const rows = document.querySelectorAll("tr");
    expect(rows.length).toBe(3);
    expect(rows[0]).toBe(first);
    expect(rows[1]).toBe(second);
    expect(rows[1].textContent).toBe("c");
    expect(rows[2].textContent).toBe("d");
  });

  it("moves a stubbed child along when what is before it goes", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p>gone</p><section data-j2-scope="s2">x</section></div>';
    const child = document.querySelector("section")!;
    morpher().patch("s1", '<div data-j2-scope="s1"><!--j2=s2--></div>', false);
    expect(document.querySelector("p")).toBeNull();
    expect(document.querySelector("section")).toBe(child);
  });

  it("reports a stub whose element is not on the page, which has drifted", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p>0</p></div>';
    expect(morpher().patch("s1", '<div data-j2-scope="s1"><p>1</p><!--j2=s9--></div>', false)).toBe(false);
    expect(document.querySelector("p")!.textContent).toBe("0");
  });
});
