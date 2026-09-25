package com.example.tspnpm.components;

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
 * Quill from npm in a TypeScript client module (NoteEditor.client.ts), with Quill's own
 * stylesheet imported from node_modules and a CSS module, and the server's validation
 * messages live inside Quill's container as a slot.
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
