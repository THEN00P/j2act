package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import j2act.LiveComponent;
import j2act.View;

/**
 * A slow page behind a withPreload(INTENT) link (ADR 0011): hovering the link starts this
 * load, so a click a moment later lands on the finished report. onSettled records when
 * the page was really opened, which a preload never does.
 */
public class ReportPage extends LiveComponent {

  @Override protected View setup() {
    var openedAt = createSignal("not yet");
    var report = createMemo(() -> async(() -> {
      Thread.sleep(800);
      return "Report ready";
    }));
    onSettled(() -> {
      openedAt.set(LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
      return null;
    });
    return () -> html(
      head(
        title("Report · j2act")
      ),
      body(
        loading(() -> h1(report.get()))
          .withFallback(h1("Building report…")),
        p("opened at " + openedAt.get()).withId("report-opened")
      )
    );
  }
}
