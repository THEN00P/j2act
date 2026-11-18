import pageStream from "../../main/java/j2act/PageStream.java?raw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

/**
 * The script a streamed page swaps its templates in with (PageStream.HELPER), read from the Java source as it is
 * compiled, and run here as the page runs it.
 */
function helper(): string {
  const java = pageStream;
  const start = java.indexOf("HELPER =");
  const body = java.slice(start, java.indexOf(";\n", start));
  let js = "";
  for (const m of body.matchAll(/"((?:[^"\\]|\\.)*)"/g)) {
    js += m[1].replace(/\\(.)/g, "$1");
  }
  return js;
}

const SVG = "http://www.w3.org/2000/svg";
const HTML = "http://www.w3.org/1999/xhtml";

describe("streamed swaps (ADR 0036)", () => {
  let j2s: (anchor: string, html: string) => void;

  beforeAll(() => {
    Object.defineProperty(document, "currentScript", { configurable: true, get: () => ({ remove() {} }) });
    j2s = new Function(helper() + ";return j2s")();
  });

  beforeEach(() => {
    document.body.innerHTML = "";
  });

  function stream(anchor: string, html: string): void {
    j2s(anchor, html);
  }

  it("swaps a region in an svg in as SVG", () => {
    document.body.innerHTML = '<svg><!--j2:s2--><circle r="1"></circle><!--/j2:s2--></svg>';
    stream("s2", '<circle r="2"></circle>');
    const circle = document.querySelector("circle")!;
    expect(circle.getAttribute("r")).toBe("2");
    expect(circle.namespaceURI).toBe(SVG);
  });

  it("swaps an element in an svg in as SVG", () => {
    document.body.innerHTML = '<svg><g data-j2-scope="s3"></g></svg>';
    stream("s3", '<g data-j2-scope="s3"><rect></rect></g>');
    expect(document.querySelector("rect")!.namespaceURI).toBe(SVG);
  });

  it("swaps a region in a foreignObject in as HTML", () => {
    document.body.innerHTML = "<svg><foreignObject><!--j2:s4--><p>a</p><!--/j2:s4--></foreignObject></svg>";
    stream("s4", "<p>b</p>");
    expect(document.querySelector("p")!.textContent).toBe("b");
    expect(document.querySelector("p")!.namespaceURI).toBe(HTML);
  });

  it("swaps an svg image in as SVG, which a template parsed as HTML would have made an img of", () => {
    document.body.innerHTML = "<svg><!--j2:s5--><!--/j2:s5--></svg>";
    stream("s5", '<!--j2:s5--><image href="a.png"></image><!--/j2:s5-->');
    const image = document.querySelector("svg")!.firstElementChild!;
    expect(image.localName).toBe("image");
    expect(image.namespaceURI).toBe(SVG);
    expect(document.querySelector("svg")!.lastChild!.nodeValue).toBe("/j2:s5");
  });
});
