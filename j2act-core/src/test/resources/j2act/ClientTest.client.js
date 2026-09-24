// The client half of ClientTest.Meter; the tests stand in for runtime.js.
import { volts } from "./client-test/units.js";

export const meter = {
  mount(el, { label, onValue }) {
    el.dataset.label = label;
    onValue(1);
  },
  read() {
    return volts(1);
  },
};
