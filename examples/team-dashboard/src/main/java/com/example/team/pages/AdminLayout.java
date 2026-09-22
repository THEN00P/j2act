package com.example.team.pages;

import static j2act.html.TagCreator.*;

import j2act.ContainerTag;
import j2act.DomContent;
import j2act.Layout;

/** Layout for /admin/*: matched child arrives as a plain child node. */
public class AdminLayout extends Layout {

  @Override public ContainerTag render(DomContent content) {
    return html(
      head(
        title("Admin"),
        link().withRel("stylesheet").withHref("/app.css")),
      body(div(
        nav(a("Users").withHref("/admin"), a("Settings").withHref("/admin/settings")),
        content,
        script().withSrc("/app.js")
      ))
    );
  }
}
