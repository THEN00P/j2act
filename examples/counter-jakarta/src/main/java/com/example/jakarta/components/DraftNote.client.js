// The browser half of DraftNote.PauseControls (ADR 0022). Pausing is the page's own call:
// j2act.pause() saves the Retained State on the server and frees the session (ADR 0026).
// @ts-check

/** @type {{ pause(): Promise<void>, resume(): Promise<void> }} */
const j2act = /** @type {any} */ (window).j2act;

/** @satisfies {import("./DraftNote.types").PauseControls} */
export const pauseControls = {
  mount(el) {
    const pause = document.createElement("button");
    pause.id = "pause";
    pause.textContent = "Pause";
    pause.onclick = () => j2act.pause();
    const note = document.createElement("p");
    note.id = "paused-note";
    note.textContent = "Paused: the session is gone and the draft waits on the server. ";
    const resume = document.createElement("button");
    resume.id = "resume";
    resume.textContent = "Resume";
    resume.onclick = () => j2act.resume();
    note.append(resume);
    el.append(pause, note);

    const show = () => {
      const paused = document.documentElement.hasAttribute("data-j2-paused");
      pause.hidden = paused;
      note.hidden = !paused;
    };
    document.addEventListener("j2act:paused", show);
    document.addEventListener("j2act:resumed", show);
    show();
    return () => {
      document.removeEventListener("j2act:paused", show);
      document.removeEventListener("j2act:resumed", show);
      pause.remove();
      note.remove();
    };
  },
};
