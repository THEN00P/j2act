// The browser half of NoteEditor.Editor (ADR 0022): Quill by bare name, through the import map.
// Quill's ES modules import quill-delta, which npm ships as CommonJS; the runtime serves that as an
// ES module, so this is a plain import with no build step.
import Quill from "quill";

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
