package com.example.counter.pages;

import static com.example.counter.components.Attachment.attachment;
import static com.example.counter.components.Publisher.publisher;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Page;
import j2act.html.tags.HtmlTag;

/** A second page to navigate to; its title wins over the layout's in the merged head (ADR 0007). */
public class AboutPage extends LiveComponent implements Page {

  @Override public HtmlTag render() {
    return html(
      head(
        title("About · j2act"),
        meta().withName("description").withContent("About this demo")
      ),
      body(
        h1("About"),
        p("Reached by soft navigation: no full page load, the layout above kept its State."),
        p(a("Jump to the form section on home").withHref("/#forms")),
        h2("Computed and mutation"),
        publisher(),
        h2("Upload"),
        attachment()
      )
    );
  }
}
