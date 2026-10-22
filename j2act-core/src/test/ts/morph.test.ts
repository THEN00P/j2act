import { beforeEach, describe, expect, it } from "vitest";
import { createMorpher, type Morpher } from "../../main/ts/morph.ts";
import { createPending } from "../../main/ts/pending.ts";

describe("Morpher keeping unchanged children (ADR 0031)", () => {
  let morpher: Morpher;

  beforeEach(() => {
    morpher = createMorpher(createPending(), () => {});
    document.body.innerHTML = '<div data-j2-scope="s1"><h2>Page 1</h2>'
      + '<table><tbody><tr data-j2-scope="s2"><td><input value="typed"></td></tr></tbody></table>'
      + '<button data-j2-scope="s3">A: 1</button></div>';
  });

  it("keeps the live element a placeholder names and morphs the rest", () => {
    const row = document.querySelector('[data-j2-scope="s2"]')!;
    const input = row.querySelector("input")!;
    input.value = "edited";

    const result = morpher.patch("s1", '<div data-j2-scope="s1"><h2>Page 2</h2>'
      + '<table><tbody><tr data-j2-unchanged="s2"></tr></tbody></table>'
      + '<button data-j2-unchanged="s3"></button></div>', false);

    expect(result).toBe("applied");
    expect(document.querySelector("h2")!.textContent).toBe("Page 2");
    expect(document.querySelector('[data-j2-scope="s2"]')).toBe(row);
    expect(row.querySelector("input")).toBe(input);
    expect(input.value).toBe("edited");
    expect(document.querySelector('[data-j2-scope="s3"]')!.textContent).toBe("A: 1");
    expect(document.querySelector("[data-j2-unchanged]")).toBeNull();
  });

  it("moves kept elements that changed places", () => {
    document.body.innerHTML = '<ul data-j2-scope="s1"><li data-j2-scope="s2"><input value="Ada"></li>'
      + '<li data-j2-scope="s3"><input value="Ken"></li></ul>';
    const ada = document.querySelector('[data-j2-scope="s2"]')!;
    ada.querySelector("input")!.value = "typed by Ada";

    morpher.patch("s1", '<ul data-j2-scope="s1"><li data-j2-unchanged="s3"></li><li data-j2-unchanged="s2"></li></ul>', false);

    const rows = Array.from(document.querySelectorAll("li"));
    expect(rows.map(li => li.getAttribute("data-j2-scope"))).toEqual(["s3", "s2"]);
    expect(rows[1]).toBe(ada);
    expect(ada.querySelector("input")!.value).toBe("typed by Ada");
    expect(document.querySelector("[id]")).toBeNull();
  });

  it("moves re-rendered components that changed places, with what the user typed", () => {
    // A free-text input: the render does not set its value (ADR 0013).
    document.body.innerHTML = '<ul data-j2-scope="s1"><li data-j2-scope="s2">Ada <input data-j2-controlled></li>'
      + '<li data-j2-scope="s3">Ken <input data-j2-controlled></li></ul>';
    const ada = document.querySelector('[data-j2-scope="s2"]')!;
    ada.querySelector("input")!.value = "typed by Ada";

    morpher.patch("s1", '<ul data-j2-scope="s1"><li data-j2-scope="s3">Ken <input data-j2-controlled></li>'
      + '<li data-j2-scope="s2">Ada <input data-j2-controlled></li></ul>', false);

    const rows = Array.from(document.querySelectorAll("li"));
    expect(rows[1]).toBe(ada);
    expect(ada.querySelector("input")!.value).toBe("typed by Ada");
    expect(document.querySelector("[id]")).toBeNull();
  });

  it("moves components whose own ids changed as they moved", () => {
    document.body.innerHTML = '<ul data-j2-scope="s1"><li id="a1" data-j2-scope="s2">Ada</li>'
      + '<li id="k1" data-j2-scope="s3">Ken</li></ul>';
    const ada = document.querySelector('[data-j2-scope="s2"]')!;

    morpher.patch("s1", '<ul data-j2-scope="s1"><li id="k2" data-j2-scope="s3">Ken</li>'
      + '<li id="a2" data-j2-scope="s2">Ada</li></ul>', false);

    const rows = Array.from(document.querySelectorAll("li"));
    expect(rows[1]).toBe(ada);
    expect(rows.map(li => li.id)).toEqual(["k2", "a2"]);
  });

  it("moves components even when the page uses an id like the temporary ones", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p id="j2k-s2">authored</p><ul><li data-j2-scope="s2">Ada</li>'
      + '<li data-j2-scope="s3">Ken</li></ul></div>';
    const ada = document.querySelector('[data-j2-scope="s2"]')!;

    morpher.patch("s1", '<div data-j2-scope="s1"><p id="j2k-s2">authored</p><ul><li data-j2-scope="s3">Ken</li>'
      + '<li data-j2-scope="s2">Ada</li></ul></div>', false);

    expect(Array.from(document.querySelectorAll("li"))[1]).toBe(ada);
    expect(document.getElementById("j2k-s2")!.textContent).toBe("authored");
  });

  it("gives a component whose root tag changed the id its render gave it", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p id="old" data-j2-scope="s2">a</p>'
      + '<p data-j2-scope="s3">b</p></div>';

    morpher.patch("s1", '<div data-j2-scope="s1"><section id="new" data-j2-scope="s2">a</section>'
      + '<span data-j2-scope="s3">b</span></div>', false);

    expect(document.querySelector('[data-j2-scope="s2"]')!.id).toBe("new");
    expect(document.querySelector('[data-j2-scope="s3"]')!.hasAttribute("id")).toBe(false);
  });

  it("leaves the ids components have of their own", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p id="note" data-j2-scope="s2">a</p></div>';

    morpher.patch("s1", '<div data-j2-scope="s1"><p id="note" data-j2-scope="s2">b</p></div>', false);

    expect(document.getElementById("note")!.textContent).toBe("b");
  });

  it("asks for a full render when a kept element is missing", () => {
    document.querySelector('[data-j2-scope="s3"]')!.remove();

    const result = morpher.patch("s1", '<div data-j2-scope="s1"><h2>Page 2</h2><button data-j2-unchanged="s3"></button></div>', false);

    expect(result).toBe("resync");
    expect(document.querySelector("h2")!.textContent).toBe("Page 1");
  });

  it("leaves every id as it was when it asks for a full render", () => {
    document.body.innerHTML = '<div data-j2-scope="s1"><p id="old" data-j2-scope="s2">a</p></div>';

    const result = morpher.patch("s1", '<div data-j2-scope="s1"><p id="new" data-j2-scope="s2">b</p>'
      + '<button data-j2-unchanged="s3"></button></div>', false);

    expect(result).toBe("resync");
    expect(document.querySelector('[data-j2-scope="s2"]')!.id).toBe("old");
  });

  it("reports a patch whose anchor is gone", () => {
    expect(morpher.patch("s9", '<p data-j2-scope="s9"></p>', false)).toBe("gone");
  });
});
