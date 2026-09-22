package com.example.team.pages;

import static j2act.html.TagCreator.*;

import com.example.team.components.Counter;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Page;

/** Public landing page. No guard, no identity seam needed. */
public class HomePage extends LiveComponent implements Page {

  @Override public ContainerTag render() {
    return html(
      head(
        title("Team dashboard"),
        meta().withName("description").withContent("Counters and team directory")),
      body(div(
        h1("Team dashboard"),
        p("Two independent counters — isolated state, isolated morph targets."),
        div(
          Counter.counter().withLabel("A"),
          Counter.counter().withLabel("B")),
        p(a("Manage users").withHref("/admin"))
      ))
    );
  }
}
