package com.example.counter.pages;

import static com.example.counter.components.ClockFeed.clockFeed;
import static com.example.counter.components.Counter.counter;
import static com.example.counter.components.NameRows.nameRows;
import static com.example.counter.components.ProfileCard.profileCard;
import static com.example.counter.components.SearchBox.searchBox;
import static j2act.html.TagCreator.*;

import com.example.counter.components.Counter;
import j2act.ContainerTag;
import j2act.LiveComponent;
import j2act.Page;
import j2act.State;

/** One page exercising every claim the first slice has to prove. */
public class HomePage extends LiveComponent implements Page {

  private static final String CSS = ""
    + "body{font:15px/1.5 system-ui,sans-serif;max-width:720px;margin:2rem auto;padding:0 1rem;color:#222}"
    + "section{border-top:1px solid #ddd;padding:.75rem 0}h2{font-size:1rem;margin:.25rem 0}"
    + "button{font:inherit;padding:.25rem .75rem;margin:.15rem;cursor:pointer}"
    + "button[data-pending]{opacity:.6;cursor:progress}.muted{color:#888}"
    + ".card{background:#f6f6f6;padding:.5rem .75rem;border-radius:6px}ul{padding-left:1.25rem}";

  private final Counter pinned = counter().withLabel("Held instance");
  private final State<Integer> pageRenders = state(0);
  private final State<Boolean> showProfile = state(true);
  private final State<String> saved = state("never");

  @Override public ContainerTag render() {
    return html(
      head(
        title("j2act · first slice"),
        style(CSS)
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
          h2("Query cache across unmount"),
          button(showProfile.get() ? "Hide profile" : "Show profile")
            .onClick(e -> showProfile.set(!showProfile.get())),
          showProfile.get()
            ? profileCard().withName("Grace Hopper")
            : null
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
