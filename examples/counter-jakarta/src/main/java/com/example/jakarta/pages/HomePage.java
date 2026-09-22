package com.example.jakarta.pages;

import static com.example.jakarta.components.ClockFeed.clockFeed;
import static com.example.jakarta.components.Counter.counter;
import static com.example.jakarta.components.GreetingForm.greetingForm;
import static com.example.jakarta.components.PeopleCount.peopleCount;
import static com.example.jakarta.components.SearchBox.searchBox;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Page;
import j2act.html.tags.HtmlTag;

/** The slice on WildFly: every section exercises one container integration. */
public class HomePage extends LiveComponent implements Page {

  @Override public HtmlTag render() {
    return html(
      head(
        title("j2act · WildFly")
      ),
      body(
        h1("j2act on WildFly"),
        section(
          h2("Counters"),
          counter().withLabel("A"),
          counter().withLabel("B")
        ),
        section(
          h2("JPA through an injected repository"),
          searchBox()
        ),
        section(
          h2("@PersistenceContext in a component"),
          peopleCount()
        ),
        section(
          h2("Push from a ManagedScheduledExecutorService"),
          clockFeed()
        ),
        section(
          h2("Form"),
          greetingForm()
        )
      )
    );
  }
}
