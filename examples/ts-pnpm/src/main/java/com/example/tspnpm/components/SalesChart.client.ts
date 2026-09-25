// The browser half of SalesChart.Bars: npm's Chart.js, a CSS module, and a helper shared with NoteEditor.
import { Chart } from "chart.js/auto";

import { total } from "../shared/numbers";
import styles from "./SalesChart.module.css";
import type { Bars } from "./SalesChart.types";

const charts = new WeakMap<HTMLDivElement, Chart<"bar">>();

function mark(el: HTMLDivElement, sales: number[]) {
  el.dataset.bars = String(sales.length);
  el.dataset.total = String(total(sales));
}

export const bars = {
  mount(el, { months, sales, legend }) {
    el.classList.add(styles.chart);
    legend.classList.add(styles.legend);
    const canvas = document.createElement("canvas");
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

  update(el, { months, sales }) {
    const chart = charts.get(el)!;
    chart.data.labels = months;
    chart.data.datasets[0].data = sales;
    chart.update();
    mark(el, sales);
  },
} satisfies Bars;
