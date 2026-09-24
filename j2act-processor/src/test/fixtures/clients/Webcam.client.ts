import type { Camera } from "./Webcam.types";

// Exports camera only, so the processor warns that beeper is missing.
export const camera = {
  mount() {},
  snapshot() {
    return "";
  },
  shot() {
    return Promise.reject(new Error("not in the fixture"));
  },
  pause() {},
} satisfies Camera;
