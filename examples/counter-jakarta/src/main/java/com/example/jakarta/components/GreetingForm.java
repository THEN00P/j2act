package com.example.jakarta.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.View;
import j2act.ComponentTag;
import j2act.Signal;
import j2act.html.tags.DivTag;

/** Form submit with an uncontrolled field and pending on the submit button (ADR 0013). */
public final class GreetingForm extends ComponentTag {

  public static GreetingForm greetingForm() {
    return new GreetingForm();
  }

  @Override protected View setup() {
    var greeting = createSignal("nobody greeted yet");
    return () -> div(
      form(
        label(
          text("Name "),
          input()
            .withId("greet-name")
            .withName("name")
        ),
        button("Greet")
          .withType("submit")
          .withPending(span("Greeting…"))
      )
        .withId("greet")
        .onSubmit(e -> {
          Thread.sleep(300);
          greeting.set("Hello, " + e.value("name").trim());
        }),
      p("Greeting: " + greeting.get())
    );
  }
}
