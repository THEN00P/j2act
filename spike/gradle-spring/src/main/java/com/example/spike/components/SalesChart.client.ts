import { Chart } from "chart.js/auto";

import styles from "./SalesChart.module.css";
import type { Bars } from "./SalesChart.types";

// The build stamp lets the checks see which build of this module is mounted.
const BUILD = "one";

const charts = new WeakMap<HTMLDivElement, Chart<"bar">>();

export const bars = {
  mount(el, { months, sales }) {
    el.classList.add(styles.chart);
    el.dataset.build = BUILD;
    const canvas = document.createElement("canvas");
    el.append(canvas);
    const chart = new Chart(canvas, {
      type: "bar",
      data: { labels: months, datasets: [{ label: "sales", data: sales }] },
      options: { animation: false, plugins: { legend: { display: false } } },
    });
    charts.set(el, chart);
    el.dataset.bars = String(sales.length);
    return () => {
      chart.destroy();
      canvas.remove();
    };
  },

  update(el, { months, sales }) {
    const chart = charts.get(el)!;
    chart.data.labels = months;
    chart.data.datasets[0].data = sales;
    chart.update();
    el.dataset.bars = String(sales.length);
  },
} satisfies Bars;
