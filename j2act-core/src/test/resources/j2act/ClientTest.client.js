// The client half of ClientTest.Meter; the tests stand in for runtime.js.
export const meter = {
  mount(el, { label, onValue }) {
    el.dataset.label = label;
    onValue(1);
  },
  read() {
    return "1 V";
  },
};
