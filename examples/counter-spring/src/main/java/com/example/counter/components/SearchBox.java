package com.example.counter.components;

import static j2act.html.TagCreator.*;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import com.example.counter.services.Directory;
import j2act.ComponentTag;
import j2act.html.tags.DivTag;
import j2act.Query;
import j2act.State;

/**
 * Keyless query (ADR 0020): filter is read inside the loader, so it is the dependency.
 * Typing fast supersedes in-flight runs; a stale result never reaches the page.
 */
public final class SearchBox extends ComponentTag {

  @Inject private Directory directory;

  private final State<String> filter = state("");
  private final Query<List<String>> people = query(() -> directory.search(filter.get()));

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
      span(people.isFetching() ? " searching…" : "")
        .withClass("muted"),
      people.get() == null
        ? p("Loading…")
        : ul(
            each(people.get(), person -> li(person))
          )
    );
  }
}
