package com.example.counter.components;

import static j2act.html.TagCreator.*;

import java.util.Locale;

import j2act.ComponentTag;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * Form events (ADR 0013): submit arrives as decoded fields with the browser's submit
 * prevented, only Enter and Escape keydowns travel, and focus/blur report the value.
 */
public final class GreetingForm extends ComponentTag {

  private final State<String> greeting = state("nobody greeted yet");
  private final State<String> focus = state("idle");
  private final State<String> lastKey = state("none");

  public static GreetingForm greetingForm() {
    return new GreetingForm();
  }

  @Override protected DivTag render() {
    return div(
      form(
        label(
          text("Name "),
          input()
            .withId("greet-name")
            .withName("name")
            .withAutocomplete("off")
            .onFocus(e -> focus.set("editing"))
            .onBlur(e -> focus.set("left with \"" + e.value() + "\""))
            .onKeyDown(e -> lastKey.set(e.key() + (e.shift() ? "+Shift" : "")))
            .withKeyFilter("Enter", "Escape")
        ),
        label(
          input()
            .withId("greet-loud")
            .withType("checkbox")
            .withName("loud")
            .withValue("yes"),
          text(" loud")
        ),
        button("Greet")
          .withType("submit")
          .withPending(span("Greeting…"))
      )
        .withId("greet")
        .onSubmit(e -> {
          Thread.sleep(400);
          String name = e.value("name").trim();
          if (name.isEmpty()) {
            greeting.set("nobody, the name was empty");
          } else if ("yes".equals(e.value("loud"))) {
            greeting.set("HELLO, " + name.toUpperCase(Locale.ROOT) + "!");
          } else {
            greeting.set("Hello, " + name);
          }
        }),
      p("Greeting: " + greeting.get()),
      small("focus: " + focus.get() + " · last filtered key: " + lastKey.get())
    );
  }
}
