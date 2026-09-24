// The browser half of Countdown.Timer (ADR 0022). Types: Countdown.types.d.ts.
// @ts-check

/** Per element: the running countdown, kept in module state, never on this. */
const running = new WeakMap();

/**
 * @param {HTMLDivElement} el
 * @param {number} seconds
 * @param {(value: number) => void} onTick
 * @param {() => void} onDone
 */
function start(el, seconds, onTick, onDone) {
  const face = /** @type {HTMLElement} */ (el.querySelector(".face"));
  let left = seconds;
  face.textContent = String(left);
  const id = setInterval(() => {
    left--;
    face.textContent = String(left);
    onTick(left);
    if (left <= 0) {
      clearInterval(id);
      onDone();
    }
  }, 1000);
  running.set(el, { onTick, onDone, stop: () => clearInterval(id), left: () => left });
}

/** @satisfies {import("./Countdown.types").Timer} */
export const timer = {
  mount(el, { seconds, labels, onTick, onDone }) {
    const face = document.createElement("strong");
    face.className = "face";
    el.prepend(face);
    el.setAttribute("data-running", "");
    el.dataset.unit = labels.unit;
    start(el, seconds, onTick, onDone);
    return () => {
      running.get(el)?.stop();
      face.remove();
    };
  },

  pause(el) {
    const state = running.get(el);
    state.stop();
    return state.left();
  },

  restart(el, seconds) {
    const state = running.get(el);
    state.stop();
    start(el, seconds, state.onTick, state.onDone);
  },

  export(el, into) {
    const text = "ticks left: " + running.get(el).left() + "\n";
    return into.send(new Blob([text], { type: "text/plain" }), "ticks.txt");
  },
};
