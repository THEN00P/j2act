// The page loads Quill's browser distribution and stylesheet (ADR 0022).
const { Quill } = window;

/** @satisfies {import("./NoteEditor.types").Editor} */
export const editor = {
  mount(el, { initial, onText, problems }) {
    const host = document.createElement("div");
    el.append(host);
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
