// The browser half of NoteEditor.Editor: npm's Quill with its stylesheet from node_modules, and a CSS module.
import Quill from "quill";
import "quill/dist/quill.snow.css";

import { total } from "../shared/numbers";
import styles from "./NoteEditor.module.css";
import type { Editor } from "./NoteEditor.types";

export const editor = {
  mount(el, { initial, onText, problems }) {
    el.classList.add(styles.editor);
    problems.classList.add(styles.problems);
    const host = document.createElement("div");
    el.append(host);
    const quill = new Quill(host, { theme: "snow", modules: { toolbar: [["bold", "italic"], ["link"]] } });
    quill.setText(initial);
    // The server's messages live inside Quill's own container, and keep updating there.
    quill.container.append(problems);
    const report = () => {
      const text = quill.getText().trimEnd();
      el.dataset.words = String(total(text.split(/\s+/).filter(Boolean).map(() => 1)));
      onText(text);
    };
    quill.on("text-change", report);
    return () => {
      quill.off("text-change", report);
      el.replaceChildren();
    };
  },
} satisfies Editor;
