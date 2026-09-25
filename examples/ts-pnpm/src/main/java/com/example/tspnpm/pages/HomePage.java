package com.example.tspnpm.pages;

import static com.example.tspnpm.components.NoteEditor.noteEditor;
import static com.example.tspnpm.components.SalesChart.salesChart;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Page;
import j2act.html.tags.HtmlTag;

/** Two TypeScript client modules with npm packages, CSS imports and CSS modules. */
public class HomePage extends LiveComponent implements Page {

  @Override public HtmlTag render() {
    return html(
      head(
        title("j2act · TypeScript with pnpm")
      ),
      body(
        h1("TypeScript client modules, bundled from pnpm"),
        section(
          h2("Chart.js with a server-rendered legend"),
          salesChart()
        ),
        section(
          h2("Quill with live server validation"),
          noteEditor()
        )
      )
    );
  }
}
