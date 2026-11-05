package com.example.jakarta.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import com.example.jakarta.model.People;
import j2act.View;
import j2act.ComponentTag;
import j2act.Signal;
import j2act.html.tags.DivTag;

/**
 * Keyless query over JPA (ADR 0020). The loader runs on the container's managed
 * executor, so the injected repository's @PersistenceContext works there.
 */
public final class SearchBox extends ComponentTag {

  @Inject private People people;

  public static SearchBox searchBox() {
    return new SearchBox();
  }

  @Override protected View setup() {
    var filter = createSignal("");
    var found = createMemo(() -> async(() -> people.search(filter.get())));
    return () -> div(
      input()
        .withId("search")
        .withPlaceholder("Filter people")
        .withValue(latest(filter))
        .withDebounce(Duration.ofMillis(150))
        .onInput(e -> filter.set(e.value())),
      span(isPending(found) ? " searching…" : ""),
      loading(() -> ul(
        each(found.get(), name -> li(name))
      )).withFallback(p("Loading…"))
    );
  }
}
