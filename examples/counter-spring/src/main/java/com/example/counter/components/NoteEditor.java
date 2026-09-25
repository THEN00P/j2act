package com.example.counter.components;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import j2act.Client;
import j2act.ComponentTag;
import j2act.DomContent;
import j2act.Mount;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * The Quill spike (ADR 0022): Quill owns the editor's DOM, and the server's validation
 * messages live inside Quill's own container as a slot, updating as the text changes.
 * That is the case wire:ignore and phx-update="ignore" leave stale.
 */
public final class NoteEditor extends ComponentTag {

  /** A rich-text editor that reports its plain text. */
  interface Editor extends Client {
    Mount<DivTag> mount(String initial, Consumer<String> onText, DomContent problems);
  }

  private static final String INITIAL = "Hello from j2act";
  private static final int LIMIT = 40;

  private final Editor editor = client(Editor.class);
  private final State<String> text = state(INITIAL);

  public static NoteEditor noteEditor() {
    return new NoteEditor();
  }

  @Override protected DivTag render() {
    return div(
      div()
        .withId("editor")
        .withClient(editor.mount(INITIAL, text::set, problems(text.get()))),
      p("server has " + text.get().length() + " characters")
        .withId("editor-count")
    );
  }

  /** Validated on the server, shown inside Quill. */
  private static DivTag problems(String text) {
    List<String> problems = new ArrayList<>();
    if (text.isBlank()) {
      problems.add("write something");
    }
    if (text.length() > LIMIT) {
      problems.add("too long: " + text.length() + " of " + LIMIT + " characters");
    }
    if (text.contains("TODO")) {
      problems.add("remove the TODO");
    }
    return div(
      problems.isEmpty() ? p("looks good") : each(problems, problem -> p(problem))
    ).withClass("editor-problems");
  }
}
