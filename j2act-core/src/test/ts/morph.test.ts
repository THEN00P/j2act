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

describe("regions between markers (ADR 0036)", () => {
  const morpher = () => createMorpher(createPending(), () => {}, createFields());

  beforeEach(() => {
    document.body.innerHTML = "";
  });

  it("patches what is between a region's markers and nothing around it", () => {
    document.body.innerHTML = '<p data-j2-scope="s1">Hello <!--j2:s2-->Ada<!--/j2:s2-->, <b>you</b></p>';
    const bold = document.querySelector("b")!;
    expect(morpher().patch("s2", "<!--j2:s2-->Grace<!--/j2:s2-->", false)).toBe(true);
    expect(document.querySelector("p")!.textContent).toBe("Hello Grace, you");
    expect(document.querySelector("b")).toBe(bold);
    expect(document.body.innerHTML).toContain("<!--j2:s2-->Grace<!--/j2:s2-->");
  });

  it("morphs elements in a region, keeping the node that stays", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><!--j2:s2--><span class="a">1</span><i>x</i><!--/j2:s2--><em>after</em></div>';
    const span = document.querySelector("span")!;
    morpher().patch("s2", '<!--j2:s2--><span class="b">2</span><!--/j2:s2-->', false);
    expect(document.querySelector("span")).toBe(span);
    expect(span.className).toBe("b");
    expect(document.querySelector("i")).toBeNull();
    expect(document.querySelector("em")!.textContent).toBe("after");
  });

  it("patches an empty region and a region holding table rows", () => {
    document.body.innerHTML = '<table><tbody data-j2-scope="s1"><!--j2:s2--><!--/j2:s2--><tr><td>last</td></tr></tbody></table>';
    morpher().patch("s2", "<!--j2:s2--><tr><td>new</td></tr><!--/j2:s2-->", false);
    const cells = [...document.querySelectorAll("td")].map((td) => td.textContent);
    expect(cells).toEqual(["new", "last"]);
  });

  it("keeps a stubbed region, nodes and all, when what is around it is patched", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><b>0</b><!--j2:s2--><span>kept</span>text<!--/j2:s2--></div>';
    const span = document.querySelector("span")!;
    morpher().patch("s1", '<div data-j2-scope="s1"><b>1</b><!--j2=s2--></div>', false);
    expect(document.querySelector("b")!.textContent).toBe("1");
    expect(document.querySelector("span")).toBe(span);
    expect(document.querySelector("div")!.textContent).toBe("1kepttext");
  });

  it("parses a region in an svg as SVG, as the page's own parse did", () => {
    document.body.innerHTML = '<svg data-j2-scope="s1"><!--j2:s2--><circle r="1"></circle><!--/j2:s2--></svg>';
    expect(morpher().patch("s2", '<!--j2:s2--><circle r="2"></circle><rect></rect><!--/j2:s2-->', false)).toBe(true);
    const svg = "http://www.w3.org/2000/svg";
    expect(document.querySelector("circle")!.namespaceURI).toBe(svg);
    expect(document.querySelector("circle")!.getAttribute("r")).toBe("2");
    expect(document.querySelector("rect")!.namespaceURI).toBe(svg);
  });

  it("parses a region in an svg's foreignObject as HTML, as the page's own parse did", () => {
    document.body.innerHTML = '<svg data-j2-scope="s1"><foreignObject><!--j2:s2--><p>a</p><!--/j2:s2--></foreignObject></svg>';
    expect(morpher().patch("s2", "<!--j2:s2--><p>b</p><div>c</div><!--/j2:s2-->", false)).toBe(true);
    const html = "http://www.w3.org/1999/xhtml";
    expect(document.querySelector("p")!.namespaceURI).toBe(html);
    expect(document.querySelector("p")!.textContent).toBe("b");
    expect(document.querySelector("div")!.namespaceURI).toBe(html);
  });

  it("reports a region that is not on the page", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"></div>';
    expect(morpher().patch("s5", "<!--j2:s5-->x<!--/j2:s5-->", false)).toBe(false);
  });
});

describe("attribute regions (ADR 0036)", () => {
  const morpher = (pending = createPending()) => createMorpher(pending, () => {}, createFields());

  beforeEach(() => {
    document.body.innerHTML = "";
  });

  it("sets and removes only the attributes it names, on the element that carries its anchor", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p class="a" title="t" data-j2-a-s7="">text</p></div>';
    const p = document.querySelector("p")!;
    expect(morpher().attrs("s7", { class: "b", hidden: "" })).toBe(true);
    expect(p.className).toBe("b");
    expect(p.hasAttribute("hidden")).toBe(true);
    expect(p.getAttribute("title")).toBe("t");
    expect(p.textContent).toBe("text");
    morpher().attrs("s7", { class: null, hidden: null });
    expect(p.hasAttribute("class")).toBe(false);
    expect(p.hasAttribute("hidden")).toBe(false);
  });

  it("asks what a morph asks: a pending element keeps its pending state", () => {
    document.body.innerHTML = '<button data-pending="" data-j2-a-s3="">go</button>';
    const button = document.querySelector("button")!;
    const pending = createPending();
    pending.has = (el: Element) => el === button;
    morpher(pending).attrs("s3", { "data-pending": null, class: "x" });
    expect(button.hasAttribute("data-pending")).toBe(true);
    expect(button.className).toBe("x");
  });

  it("reports an element that is not on the page", () => {
    expect(morpher().attrs("s9", { class: "x" })).toBe(false);
  });
});

describe("keyed list operations (ADR 0036)", () => {
  const morpher = () => createMorpher(createPending(), () => {}, createFields());
  const row = (anchor: string, text: string) => "<!--j2:" + anchor + "--><li>" + text + "</li><!--/j2:" + anchor + "-->";

  beforeEach(() => {
    document.body.innerHTML = "";
  });

  function list(...rows: string[]): void {
    document.body.innerHTML = '<ul data-j2-scope="s1"><!--j2:s2-->' + rows.join("") + "<!--/j2:s2--></ul><p>after</p>";
  }

  const texts = () => [...document.querySelectorAll("li")].map((li) => li.textContent);

  it("moves rows with their nodes, inserts, removes and patches, leaving the rest alone", () => {
    list(row("s3", "a"), row("s4", "b"), row("s5", "c"));
    const [a, b, c] = [...document.querySelectorAll("li")];
    b.setAttribute("data-mine", "yes");
    expect(morpher().list("s2", [
      { r: "s5" },
      { i: "s6", a: null, h: row("s6", "d") },
      { m: "s4", a: "s6" },
      { p: "s3", h: row("s3", "a!") }
    ])).toBe(true);
    expect(texts()).toEqual(["d", "b", "a!"]);
    const now = [...document.querySelectorAll("li")];
    expect(now[1]).toBe(b);
    expect(b.getAttribute("data-mine")).toBe("yes");
    expect(now[2]).toBe(a);
    expect(c.isConnected).toBe(false);
    expect(document.querySelector("p")!.textContent).toBe("after");
  });

  it("inserts a row of an svg as SVG", () => {
    document.body.innerHTML = '<svg data-j2-scope="s1"><!--j2:s2--><!--j2:s3--><circle></circle><!--/j2:s3--><!--/j2:s2--></svg>';
    expect(morpher().list("s2", [{ i: "s4", a: "s3", h: "<!--j2:s4--><circle></circle><!--/j2:s4-->" }])).toBe(true);
    const circles = [...document.querySelectorAll("circle")];
    expect(circles.length).toBe(2);
    expect(circles[1].namespaceURI).toBe("http://www.w3.org/2000/svg");
  });

  it("keeps a focused field in a row that moves", () => {
    list("<!--j2:s3--><li><input id=\"f\"></li><!--/j2:s3-->", row("s4", "b"));
    const input = document.getElementById("f") as HTMLInputElement;
    input.focus();
    morpher().list("s2", [{ m: "s4", a: null }]);
    expect(texts()).toEqual(["b", ""]);
    expect(document.activeElement).toBe(input);
  });

  it("reports a row it names that is not on the page", () => {
    list(row("s3", "a"));
    expect(morpher().list("s2", [{ m: "s9", a: null }])).toBe(false);
    expect(morpher().list("s7", [{ r: "s3" }])).toBe(false);
  });
});
