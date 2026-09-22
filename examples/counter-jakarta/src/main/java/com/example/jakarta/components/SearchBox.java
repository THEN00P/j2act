package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import com.example.jakarta.model.People;
import j2act.ComponentTag;
import j2act.Query;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * Keyless query over JPA (ADR 0020). The loader runs on the container's managed
 * executor, so the injected repository's @PersistenceContext works there.
 */
public final class SearchBox extends ComponentTag {

  @Inject private People people;

  private final State<String> filter = state("");
  private final Query<List<String>> found = query(() -> people.search(filter.get()));

  public static SearchBox searchBox() {
    return new SearchBox();
  }

  @Override protected DivTag render() {
    return div(
      input()
        .withId("search")
        .withPlaceholder("Filter people")
        .withValue(filter.get())
        .withDebounce(Duration.ofMillis(150))
        .onInput(e -> filter.set(e.value())),
      span(found.isFetching() ? " searching…" : ""),
      found.get() == null
        ? p("Loading…")
        : ul(
            each(found.get(), name -> li(name))
          )
    );
  }
}
