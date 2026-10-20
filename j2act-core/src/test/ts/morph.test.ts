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

  it("keeps elements that moved", () => {
    document.body.innerHTML = '<ul data-j2-scope="s1"><li data-j2-scope="s2">Ada</li><li data-j2-scope="s3">Ken</li></ul>';

    morpher.patch("s1", '<ul data-j2-scope="s1"><li data-j2-unchanged="s3"></li><li data-j2-unchanged="s2"></li></ul>', false);

    const names = Array.from(document.querySelectorAll("li")).map(li => li.getAttribute("data-j2-scope") + "=" + li.textContent);
    expect(names).toEqual(["s3=Ken", "s2=Ada"]);
  });

  it("asks for a full render when a kept element is missing", () => {
    document.querySelector('[data-j2-scope="s3"]')!.remove();

    const result = morpher.patch("s1", '<div data-j2-scope="s1"><h2>Page 2</h2><button data-j2-unchanged="s3"></button></div>', false);

    expect(result).toBe("resync");
    expect(document.querySelector("h2")!.textContent).toBe("Page 1");
  });

  it("reports a patch whose anchor is gone", () => {
    expect(morpher.patch("s9", '<p data-j2-scope="s9"></p>', false)).toBe("gone");
  });
});
