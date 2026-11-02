package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import com.example.counter.services.Directory;
import j2act.View;
import j2act.ComponentTag;
import j2act.html.tags.DivTag;
import j2act.Signal;

/**
 * An async memo (ADR 0031): filter is read inside the load, so it is the dependency. Typing fast
 * supersedes loads in flight; a stale result never reaches the page. The field reads ahead of the
 * held write with latest, since what is typed must show at once.
 */
public final class SearchBox extends ComponentTag {

  @Inject private Directory directory;

  public static SearchBox searchBox() {
    return new SearchBox();
  }

  @Override protected View setup() {
    var filter = createSignal("");
    var people = createMemo(() -> {
      var query = filter.get();
      return async(() -> directory.search(query));
    });
    return () -> div(
      input()
        .withId("search")
        .withPlaceholder("Filter people")
        .withValue(latest(filter))
        .withDebounce(Duration.ofMillis(150))
        .onInput(e -> filter.set(e.value())),
      span(isPending(people) ? " searching…" : "")
        .withClass("muted"),
      loading(() -> ul(
        each(people.get(), person -> li(person))
      )).withFallback(p("Loading…"))
    );
  }
}
