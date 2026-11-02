package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.View;
import j2act.LiveComponent;
import j2act.html.tags.HtmlTag;

/** Rendered for notFound(), inside the app layout, with a 404 status. */
public class NotFoundPage extends LiveComponent {

  @Override protected View setup() {
    return () -> html(
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
