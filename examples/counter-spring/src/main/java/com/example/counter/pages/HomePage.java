package com.example.counter.pages;

import static j2act.Reactive.*;
import static com.example.counter.components.RewriteBox.rewriteBox;
import static com.example.counter.components.BrowserApis.browserApis;
import static com.example.counter.components.ClockFeed.clockFeed;
import static com.example.counter.components.NoteEditor.noteEditor;
import static com.example.counter.components.SalesChart.salesChart;
import static com.example.counter.components.Countdown.countdown;
import static com.example.counter.components.Counter.counter;
import static com.example.counter.components.DraftNote.draftNote;
import static com.example.counter.components.PlayerBar.playerBar;
import static com.example.counter.components.GreetingForm.greetingForm;
import static com.example.counter.components.NameRows.nameRows;
import static com.example.counter.components.ProfileCard.profileCard;
import static com.example.counter.components.SearchBox.searchBox;
import static j2act.html.TagCreator.*;

import com.example.counter.components.Counter;
import j2act.View;
import j2act.html.tags.HtmlTag;
import j2act.LiveComponent;
import j2act.Signal;

/** One page exercising every claim the first slice has to prove. */
public class HomePage extends LiveComponent {

  private final Counter pinned = counter().withLabel("Held instance");

  @Override protected View setup() {
    var pageRenders = createSignal(0);
    var showProfile = createSignal(true);
    var saved = createSignal("never");
    return () -> html(
      head(
        title("j2act · first slice")
      ),
      body(
        h1("j2act first slice"),
        section(
          h2("Counters: separate slots, targeted morphs"),
          counter().withLabel("A"),
          counter().withLabel("B"),
          pinned,
          button("Re-render page (" + pageRenders.get() + ")")
            .onClick(e -> pageRenders.set(pageRenders.get() + 1))
        ),
        section(
          h2("Keyless query with validated runs"),
          searchBox()
        ),
        section(
          h2("Keyed stateful rows"),
          nameRows()
        ),
        section(
          h2("Push from a Spring scheduler thread"),
          clockFeed()
        ),
        section(
          h2("A load lives as long as its slot"),
          button(showProfile.get() ? "Hide profile" : "Show profile")
            .onClick(e -> showProfile.set(!showProfile.get())),
          showProfile.get()
            ? profileCard().withName("Grace Hopper")
            : null
        ),
        section(
          h2("Forms, keys and focus").withId("forms"),
          greetingForm()
        ),
        section(
          h2("Retained State: a draft that outlives the in-memory tab"),
          draftNote()
        ),
        section(
          h2("Browser state: shared live by every tab of this browser"),
          playerBar()
        ),
        section(
          h2("Client module: ticks in the browser, caption from the server"),
          countdown()
        ),
        section(
          h2("window(): browser APIs from Java"),
          browserApis()
        ),
        section(
          h2("Spikes: Chart.js with a server legend, Quill with live server errors"),
          salesChart(),
          noteEditor()
        ),
        section(
          h2("Fields the server edits"),
          rewriteBox()
        ),
        section(
          h2("Pending UI"),
          button("Save")
            .withPending(span("Saving…"))
            .onClick(e -> {
              Thread.sleep(800);
              saved.set(java.time.LocalTime.now().withNano(0).toString());
            }),
          span(" last saved: " + saved.get())
            .withClass("muted")
        )
      )
    );
  }
}
