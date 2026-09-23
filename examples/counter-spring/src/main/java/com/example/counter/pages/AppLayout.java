package com.example.counter.pages;

import static j2act.html.TagCreator.*;

import j2act.DomContent;
import j2act.Layout;
import j2act.Preload;
import j2act.State;
import j2act.html.tags.HtmlTag;

/**
 * Shared layout: links are plain a() tags, and soft navigation keeps this layout (and
 * its State) mounted while the page inside changes (ADR 0011).
 */
public class AppLayout extends Layout {

  private static final String CSS = ""
    + "body{font:15px/1.5 system-ui,sans-serif;max-width:720px;margin:2rem auto;padding:0 1rem;color:#222}"
    + "nav{display:flex;gap:1rem;align-items:center;border-bottom:1px solid #ddd;padding-bottom:.5rem}"
    + "section{border-top:1px solid #ddd;padding:.75rem 0}h2{font-size:1rem;margin:.25rem 0}"
    + "button{font:inherit;padding:.25rem .75rem;margin:.15rem;cursor:pointer}"
    + "button[data-pending]{opacity:.6;cursor:progress}.muted{color:#888}"
    + ".card{background:#f6f6f6;padding:.5rem .75rem;border-radius:6px}ul{padding-left:1.25rem}";

  private final State<Integer> clicks = state(0);

  @Override public HtmlTag render(DomContent content) {
    return html(
      head(
        title("j2act"),
        style(CSS)
      ),
      body(
        nav(
          a("Home").withHref("/"),
          a("About").withHref("/about"),
          a("Item 7").withHref("/items/7?tab=specs"),
          a("Item 8").withHref("/items/8"),
          a("Missing").withHref("/items/404"),
          a("Report").withHref("/report").withPreload(Preload.INTENT),
          button("layout clicks " + clicks.get())
            .withId("layout-clicks")
            .onClick(e -> clicks.set(clicks.get() + 1))
        ),
        content
      )
    );
  }
}
