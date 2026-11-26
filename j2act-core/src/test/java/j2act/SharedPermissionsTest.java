package j2act;

import static j2act.Reactive.createStore;
import static j2act.Reactive.useSharedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import j2act.internal.json.Json;

/**
 * ADR 0037: permissions are what was rendered. A button shows when the page's user may use it, and a click on it runs
 * only if what it was rendered from is still so where the store is, even when the page has not heard of a change yet.
 */
class SharedPermissionsTest {

  public static class Message {
    private long id;
    private String owner;
    private String text;

    Message() {
    }

    Message(long id, String owner, String text) {
      this.id = id;
      this.owner = owner;
      this.text = text;
    }

    public long getId() {
      return id;
    }

    public String getOwner() {
      return owner;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }
  }

  @SharedScoped
  public static class Chat extends Store {
    final Stored<List<Message>> messages = createStore(new ArrayList<>(Arrays.asList(
      new Message(1, "alice", "one"), new Message(2, "bob", "two"))));
    final Stored<Set<String>> admins = createStore(new LinkedHashSet<>(Arrays.asList("alice")));

    void delete(long id) {
      messages.update(list -> list.removeIf(m -> m.getId() == id));
    }
  }

  /** A message's buttons: delete for an admin, edit for its owner, each a component of its own. */
  static final class MessageRow extends LiveComponent {
    private final long id;

    MessageRow(long id) {
      this.id = id;
    }

    @Override protected View setup() {
      Chat chat = useSharedStore(Chat.class, "room");
      String user = Reactive.auth().name();
      return () -> {
        Message m = chat.messages.get().stream().filter(x -> x.getId() == id).findFirst().orElse(null);
        if (m == null) {
          return T.span("gone" + id + ";");
        }
        return T.div(
          T.span("m" + id + ":" + m.getText() + ";"),
          new AdminButton(id),
          user.equals(m.getOwner()) ? T.button("edit" + id).onClick(e -> chat.messages.update(list -> {
            for (Message x : list) {
              if (x.getId() == id) {
                x.setText(x.getText() + "!");
              }
            }
          })) : null);
      };
    }
  }

  /** What decides the delete button is read here: whether this page's user is an admin. */
  static final class AdminButton extends LiveComponent {
    private final long id;

    AdminButton(long id) {
      this.id = id;
    }

    @Override protected View setup() {
      Chat chat = useSharedStore(Chat.class, "room");
      String user = Reactive.auth().name();
      return () -> chat.admins.get().contains(user) ? T.div(T.button("delete" + id).onClick(e -> chat.delete(id)))
        : T.div();
    }
  }

  static final class ChatPage extends LiveComponent {
    @Override protected View setup() {
      Chat chat = useSharedStore(Chat.class, "room");
      return () -> T.page("c", T.div(
        T.each(chat.messages.get().stream().map(Message::getId).collect(Collectors.toList()),
          id -> new MessageRow(id).withKey(id)),
        T.span("count:" + chat.messages.get().size() + ";")));
    }
  }

  private static BrowserStoreTest.Page open(Harness h, String user) {
    return new BrowserStoreTest.Page(h.engine, "/", BrowserStoreTest.withCookieAs(null, user)).connect();
  }

  /** Holds the tab's lane until released, so what is sent to it meanwhile waits in order behind this. */
  private static CountDownLatch block(Harness h, BrowserStoreTest.Page page) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocked = new CountDownLatch(1);
    h.engine.tab(page.tab).post(() -> {
      blocked.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    assertTrue(blocked.await(5, TimeUnit.SECONDS));
    return release;
  }

  private static int acks;

  /** Clicks, and answers whether the server ran the handler. */
  private static String clickWithoutWaiting(BrowserStoreTest.Page page, String label) {
    String id = "c" + (++acks);
    page.engine.onMessage(page.conn, Json.object("type", "event", "id", id, "handler", Harness.clickOn(page.html, label),
      "value", ""));
    return id;
  }

  private static boolean ran(BrowserStoreTest.Page page, String ack) {
    java.util.Map<String, String> reply = page.conn.await(0, m -> "ack".equals(m.get("type")) && ack.equals(m.get("id")));
    return "true".equals(reply.get("ok"));
  }

  private static List<Long> ids(Chat chat) {
    return chat.messages.get().stream().map(Message::getId).collect(Collectors.toList());
  }

  @Test
  void aClickFromBeforeARevocationTheStoreHasIsRefused() throws Exception {
    try (Harness h = BrowserStoreTest.harness(ChatPage::new)) {
      BrowserStoreTest.Page alice = open(h, "alice");
      assertTrue(alice.html.contains("delete1") && alice.html.contains("delete2"), alice.html);
      Chat chat = h.engine.sharedStore(Chat.class, "room");
      CountDownLatch release = block(h, alice);
      String click = clickWithoutWaiting(alice, "delete2");
      // Alice stops being an admin, at the store, while her page still shows her the button.
      chat.admins.update(set -> set.remove("alice"));
      Harness.eventually(() -> !chat.admins.get().contains("alice"), "the store has it");
      release.countDown();
      assertFalse(ran(alice, click), "the click waited for the page to catch up, and its button was gone");
      assertEquals(1, h.engine.stats.heldEvents.get());
      assertEquals(Arrays.asList(1L, 2L), ids(chat), "nothing was deleted");
      Harness.eventually(() -> alice.conn.since(0, m -> "patch".equals(m.get("type"))).size() >= 2,
        "the page catches up: both delete buttons go");
    }
  }

  @Test
  void aNewMessageDoesNotRefuseAClickOnAnother() throws Exception {
    try (Harness h = BrowserStoreTest.harness(ChatPage::new)) {
      BrowserStoreTest.Page alice = open(h, "alice");
      Chat chat = h.engine.sharedStore(Chat.class, "room");
      CountDownLatch release = block(h, alice);
      String click = clickWithoutWaiting(alice, "delete2");
      chat.messages.update(list -> list.add(new Message(3, "bob", "three")));
      Harness.eventually(() -> chat.messages.get().size() == 3, "the store has it");
      release.countDown();
      assertTrue(ran(alice, click), "the click ran");
      Harness.eventually(() -> ids(chat).equals(Arrays.asList(1L, 3L)), "message 2 went: " + ids(chat));
      assertEquals(0, h.engine.stats.heldEvents.get(), "nothing its button was rendered from changed");
    }
  }

  @Test
  void anEditOfAMessageNoLongerItsOwnersIsRefused() throws Exception {
    try (Harness h = BrowserStoreTest.harness(ChatPage::new)) {
      BrowserStoreTest.Page alice = open(h, "alice");
      assertTrue(alice.html.contains("edit1") && !alice.html.contains("edit2"), alice.html);
      Chat chat = h.engine.sharedStore(Chat.class, "room");
      CountDownLatch release = block(h, alice);
      String click = clickWithoutWaiting(alice, "edit1");
      // Message 1 is replaced by one of bob's with the same id: its owner changed.
      chat.messages.update(list -> list.set(0, new Message(1, "bob", "taken")));
      Harness.eventually(() -> "bob".equals(chat.messages.get().get(0).getOwner()), "the store has it");
      release.countDown();
      assertFalse(ran(alice, click), "the click waited, and the edit button was gone");
      assertEquals("taken", chat.messages.get().get(0).getText());
    }
  }

  /** Alice's own last click changed what her next click's button was rendered from: it waits, and runs. */
  @Test
  void aPageBehindOnlyOnItsOwnWritesIsNotRefused() throws Exception {
    try (Harness h = BrowserStoreTest.harness(ChatPage::new)) {
      BrowserStoreTest.Page alice = open(h, "alice");
      Chat chat = h.engine.sharedStore(Chat.class, "room");
      CountDownLatch release = block(h, alice);
      String first = clickWithoutWaiting(alice, "edit1");
      String second = clickWithoutWaiting(alice, "edit1");
      release.countDown();
      ran(alice, first);
      ran(alice, second);
      Harness.eventually(() -> "one!!".equals(chat.messages.get().get(0).getText()),
        "both edits landed: " + chat.messages.get().get(0).getText());
    }
  }

  /** A click on a button that reads nothing shared runs at once, whatever changed elsewhere meanwhile. */
  @Test
  void aClickOnWhatReadNothingThatChangedDoesNotWait() throws Exception {
    try (Harness h = BrowserStoreTest.harness(ChatPage::new)) {
      BrowserStoreTest.Page alice = open(h, "alice");
      Chat chat = h.engine.sharedStore(Chat.class, "room");
      CountDownLatch release = block(h, alice);
      String click = clickWithoutWaiting(alice, "delete1");
      chat.messages.update(list -> list.get(1).setText("changed"));
      Harness.eventually(() -> "changed".equals(chat.messages.get().get(1).getText()), "the store has it");
      release.countDown();
      assertTrue(ran(alice, click));
      assertEquals(0, h.engine.stats.heldEvents.get());
    }
  }

  @Test
  void aKeyedRowsOldButtonDiesWhenTheRowShowsAnotherMessage() throws Exception {
    try (Harness h = BrowserStoreTest.harness(ChatPage::new)) {
      BrowserStoreTest.Page alice = open(h, "alice");
      String before = Harness.clickOn(alice.html, "delete1");
      Chat chat = h.engine.sharedStore(Chat.class, "room");
      int from = alice.conn.size();
      chat.delete(1);
      alice.awaitPatch(from, "count:1;");
      String id = "k1";
      alice.engine.onMessage(alice.conn, Json.object("type", "event", "id", id, "handler", before, "value", ""));
      assertFalse(ran(alice, id), "the old id is gone with its row");
      assertEquals(Arrays.asList(2L), ids(chat), "message 2 is still there");
    }
  }
}
