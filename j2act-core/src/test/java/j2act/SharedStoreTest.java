package j2act;

import static j2act.Reactive.auth;
import static j2act.Reactive.createEffect;
import static j2act.Reactive.createRetained;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.useSharedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0037: @SharedScoped stores, one per key for everyone who reads them. */
class SharedStoreTest {

  static final AtomicInteger made = new AtomicInteger();

  @SharedScoped
  public static class Room extends Store {
    final String name = "room " + sharedKey();
    final Signal<List<String>> messages = createSignal(new ArrayList<>());
    final Signal<Integer> visits = createRetained(0);
    final Signal<String> reached = createSignal("-");

    public Room() {
      made.incrementAndGet();
      createEffect(() -> 1, once -> {
        try {
          auth();
          reached.set("read a user");
        } catch (IllegalStateException e) {
          reached.set("refused");
        }
        return null;
      });
    }

    void post(String text) {
      messages.update(list -> {
        List<String> next = new ArrayList<>(list);
        next.add(text);
        return next;
      });
    }
  }

  /** A room page: the room is the query's "room", and a toggle takes the room off the page. */
  static final class RoomPage extends LiveComponent {
    @Override protected View setup() {
      var shown = createSignal(true);
      return () -> T.page("r", T.div(
        shown.get() ? new RoomView() : T.span("away;"),
        T.input().withId("post").onChange(e -> useSharedStore(Room.class, Reactive.queryParam("room")).post(e.value())),
        T.button("leave").onClick(e -> shown.set(false)),
        T.button("visit").onClick(e -> {
          Room room = useSharedStore(Room.class, Reactive.queryParam("room"));
          room.visits.update(n -> n + 1);
        })));
    }
  }

  static final class RoomView extends LiveComponent {
    @Override protected View setup() {
      Room room = useSharedStore(Room.class, Reactive.queryParam("room"));
      return () -> T.span(room.name + ":" + String.join(",", room.messages.get()) + ";visits:" + room.visits.get()
        + ";reached:" + room.reached.get() + ";");
    }
  }

  private static BrowserStoreTest.Page open(Harness h, String room, String user) {
    return new BrowserStoreTest.Page(h.engine, "/?room=" + room, BrowserStoreTest.withCookieAs(null, user)).connect();
  }

  @Test
  void twoUsersInTwoBrowsersShareARoomAndAnotherRoomIsItsOwn() {
    try (Harness h = BrowserStoreTest.harness(RoomPage::new)) {
      BrowserStoreTest.Page alice = open(h, "a", "alice");
      BrowserStoreTest.Page bob = open(h, "a", "bob");
      BrowserStoreTest.Page carol = open(h, "b", "carol");
      assertTrue(bob.html.contains("room a:;"), bob.html);
      assertTrue(carol.html.contains("room b:;"), carol.html);
      int fromBob = bob.conn.size();
      int fromCarol = carol.conn.size();
      int fromAlice = alice.conn.size();
      post(alice, "hi");
      bob.awaitPatch(fromBob, "room a:hi;");
      alice.awaitPatch(fromAlice, "room a:hi;");
      Harness.eventually(() -> h.engine.shared.size() == 2, "one store per key");
      assertTrue(carol.conn.since(fromCarol, m -> "patch".equals(m.get("type"))).isEmpty(), "room b heard nothing");
    }
  }

  @Test
  void itsCodeCannotReadAUser() {
    try (Harness h = BrowserStoreTest.harness(RoomPage::new)) {
      BrowserStoreTest.Page alice = open(h, "a", "alice");
      int from = alice.conn.size();
      Harness.eventually(() -> {
        String html = alice.html;
        return html.contains("reached:refused;") || !alice.conn.since(from,
          m -> "patch".equals(m.get("type")) && m.get("html").contains("reached:refused;")).isEmpty();
      }, "auth() in the store is refused");
    }
  }

  @Test
  void aRoomNobodyHoldsEndsAndKeepsOnlyItsRetainedValues() {
    made.set(0);
    try (Harness h = BrowserStoreTest.harness(RoomPage::new)) {
      BrowserStoreTest.Page alice = open(h, "c", "alice");
      int from = alice.conn.size();
      post(alice, "hello");
      alice.awaitPatch(from, "room c:hello;");
      from = alice.conn.size();
      alice.click("visit");
      alice.awaitPatch(from, "visits:1;");
      Store before = h.engine.shared.values().iterator().next().store;
      // The page's handlers hold it too, for as long as the tab lives: the tab goes.
      alice.bye();
      Harness.eventually(() -> h.engine.shared.isEmpty(), "the room ended after the grace window");
      BrowserStoreTest.Page again = open(h, "c", "bob");
      assertTrue(again.html.contains("room c:;visits:1;"), "only the retained value came back: " + again.html);
      Store after = h.engine.shared.values().iterator().next().store;
      assertNotSame(before, after, "a new instance");
      assertEquals(2, made.get());
    }
  }

  @Test
  void aComponentThatLeavesLetsGo() {
    try (Harness h = BrowserStoreTest.harness(RoomPage::new)) {
      BrowserStoreTest.Page alice = open(h, "d", "alice");
      SharedHub entry = h.engine.shared.values().iterator().next();
      Harness.eventually(() -> holders(entry) == 1, "the view holds it: " + holders(entry));
      int from = alice.conn.size();
      alice.click("leave");
      alice.awaitPatch(from, "away;");
      Harness.eventually(() -> holders(entry) == 0, "nothing holds it: " + holders(entry));
      Harness.eventually(() -> h.engine.shared.isEmpty(), "and it ends");
    }
  }

  @Test
  void codeOutsideAnyTabWritesToARoom() {
    try (Harness h = BrowserStoreTest.harness(RoomPage::new)) {
      BrowserStoreTest.Page alice = open(h, "e", "alice");
      int from = alice.conn.size();
      Room room = h.engine.sharedStore(Room.class, "e");
      assertSame(h.engine.shared.values().iterator().next().store, room);
      Thread writer = new Thread(() -> room.post("from a listener"));
      writer.start();
      alice.awaitPatch(from, "room e:from a listener;");
    }
  }

  private static int acks;

  /** Types text into the page's post field, without waiting for a patch of its own. */
  private static void post(BrowserStoreTest.Page page, String text) {
    String ack = "p" + (++acks);
    int from = page.conn.size();
    page.engine.onMessage(page.conn, j2act.internal.json.Json.object("type", "event", "id", ack, "handler",
      Harness.handlerOn(page.html, "change", "post"), "value", text));
    page.conn.await(from, m -> "ack".equals(m.get("type")) && ack.equals(m.get("id")));
  }

  private static int holders(SharedHub entry) {
    synchronized (entry) {
      return entry.holders;
    }
  }
}
