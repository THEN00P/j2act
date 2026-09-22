package com.example.team.pages;

import static com.example.team.components.ActivityFeed.activityFeed;
import static com.example.team.components.Counter.counter;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Page;
import j2act.html.tags.HtmlTag;

/** Public landing page. No guard, no identity seam needed. */
public class HomePage extends LiveComponent implements Page {

  @Override public HtmlTag render() {
    return html(
      head(
        title("Team dashboard"),
        meta()
          .withName("description")
          .withContent("Counters, live activity and team directory")
      ),
      body(
        div(
          h1("Team dashboard"),
          p("Two independent counters — separate tree slots, separate State and morph targets."),
          div(
            counter()
              .withLabel("A"),
            counter()
              .withLabel("B")
          ),
          h2("Activity"),
          activityFeed(),
          p(
            a("Manage users")
              .withHref("/admin")
          )
        )
      )
    );
  }
}
