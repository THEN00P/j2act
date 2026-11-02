package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import j2act.View;
import j2act.LiveComponent;
import j2act.Query;
import j2act.Signal;
import j2act.html.tags.HtmlTag;

/**
 * A slow page behind a withPreload(INTENT) link (ADR 0011): hovering the link starts this
 * query, so a click a moment later lands on the finished report. The effect records when
 * the page was really opened, which a preload never does.
 */
public class ReportPage extends LiveComponent {

  private Signal<String> openedAt;


  public ReportPage() {
    effect(() -> {
      openedAt.set(LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
      return null;
    });
  }

  @Override protected View setup() {
    openedAt = createSignal("not yet");
    var report = createMemo(() -> async(() -> {
    Thread.sleep(800);
    return "Report ready";
  }));
    return () -> html(
      head(
        title("Report · j2act")
      ),
      body(
        h1(report.isPending() ? "Building report…" : report.get()),
        p("opened at " + openedAt.get()).withId("report-opened")
      )
    );
  }
}
