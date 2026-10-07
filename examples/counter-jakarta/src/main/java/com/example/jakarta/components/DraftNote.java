package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * Retained State (ADR 0026): the draft outlives the session for this page load, such as a
 * laptop lid closed past the grace window or a restart, while a reload still clears it.
 */
public final class DraftNote extends ComponentTag {

  private final State<String> draft = retainedState("");

  public static DraftNote draftNote() {
    return new DraftNote();
  }

  @Override protected DivTag render() {
    return div(
      label(
        text("Draft "),
        input()
          .withId("draft")
          .withAutocomplete("off")
          .withValue(draft.get())
          .onChange(e -> draft.set(e.value()))
      ),
      p("Saved draft: " + (draft.get().isEmpty() ? "none" : draft.get())).withId("draft-echo")
    );
  }
}
