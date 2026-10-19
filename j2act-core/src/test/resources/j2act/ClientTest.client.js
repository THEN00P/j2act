// The client half of ClientTest.Meter; the tests stand in for runtime.js.
// Public browser URLs are left for the browser, including dynamic imports.
const volts = (n) => n + " V";
const example = "require('unused') and import './not-a-dependency.js'";

export const meter = {
  mount(el, { label, onValue }) {
    el.dataset.label = label;
    onValue(1);
  },
  read() {
    return volts(1);
  },
};
