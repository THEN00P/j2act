// The browser half of NoteEditor.Editor (ADR 0022): Quill by bare name, through the import map.
// @ts-check

// Quill's ESM build imports quill-delta, which npm ships only as CommonJS, so without a bundler
// the browser cannot load it. Quill's own browser bundle has everything inside and sets self.Quill.
import "quill/dist/quill.js";

const Quill = /** @type {any} */ (globalThis).Quill;

/** @satisfies {import("./NoteEditor.types").Editor} */
export const editor = {
  mount(el, { initial, onText, problems }) {
    // Quill's stylesheet resolves through the same import map, context path included.
    const style = document.createElement("link");
    style.rel = "stylesheet";
    style.href = import.meta.resolve("quill/dist/quill.snow.css");
    const host = document.createElement("div");
    el.append(style, host);
    const quill = new Quill(host, { theme: "snow", modules: { toolbar: [["bold", "italic"], ["link"]] } });
    quill.setText(initial);
    // The server's messages live inside Quill's own container, and keep updating there.
    quill.container.append(problems);
    const report = () => onText(quill.getText().trimEnd());
    quill.on("text-change", report);
    return () => {
      quill.off("text-change", report);
      el.replaceChildren();
    };
  },
};
