package com.example.counter.pages;

import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Page;
import j2act.html.tags.HtmlTag;

/** Rendered for notFound(), inside the app layout, with a 404 status. */
public class NotFoundPage extends LiveComponent implements Page {

  @Override public HtmlTag render() {
    return html(
      head(
        title("Not found · j2act")
      ),
      body(
        h1("Nothing here"),
        p(a("Back home").withHref("/"))
      )
    );
  }
}
