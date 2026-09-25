// The browser half of SalesChart.Bars (ADR 0022): Chart.js by bare name, through the import map.
import { Chart } from "chart.js/auto";

/** Per element: its Chart, kept in module state. */
const charts = new WeakMap();

/**
 * @param {HTMLDivElement} el
 * @param {number[]} sales
 */
function mark(el, sales) {
  el.dataset.bars = String(sales.length);
  el.dataset.total = String(sales.reduce((a, b) => a + b, 0));
}

/** @satisfies {import("./SalesChart.types").Bars} */
export const bars = {
  mount(el, { months, sales, legend }) {
    const canvas = document.createElement("canvas");
    // The server's legend goes inside the chart's own box, after the canvas.
    el.append(canvas, legend);
    const chart = new Chart(canvas, {
      type: "bar",
      data: { labels: months, datasets: [{ label: "sales", data: sales }] },
      options: { animation: false, plugins: { legend: { display: false } } },
    });
    charts.set(el, chart);
    mark(el, sales);
    return () => chart.destroy();
  },

  // New data updates the chart in place instead of rebuilding it.
  update(el, { months, sales }) {
    const chart = charts.get(el);
    chart.data.labels = months;
    chart.data.datasets[0].data = sales;
    chart.update();
    mark(el, sales);
  },
};
