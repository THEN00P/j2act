# Shared state across users

> Proposed. Changes ADR 0030's "Browser state" and ADR 0033's "One `Runtime` per browser" and its first rejected alternative. Replaces the "one owner per key" direction at the end of ADR 0033 and in `docs/spikes/shared-scope.md`.

A chat room, a Discord-like server with its channels, a shop's live bookkeeping: state that many people look at and change at once. Today j2act can share state between the tabs of one browser and no further. This ADR lets any page read and write state that other users' pages also read, and keeps the code the developer writes the same as for state that belongs to one page:

```java
@Shared
public class ChatRoom extends Store {
  private final State<List<Message>> messages = state(List.of());

  public List<Message> messages() { return messages.get(); }
  public void post(Message m) { messages.update(list -> plus(list, m)); }
  public void delete(Message m) { messages.update(list -> minus(list, m)); }
}

// in a page
ChatRoom room = shared(ChatRoom.class, roomId);
return () -> chat(
  room.messages().stream().map(message ->
    message(
      user.isAdmin() ? delButton(() -> room.delete(message)) : null,
      user.isMessageOwner(message) ? editButton() : null,
      message.getContent()
    )
  )
);
```

When Alice posts, `messages` changes, and every page that read it renders again with its own `user`. Alice sees an edit button on her message, an admin sees a delete button, and Bob sees the text. Nobody writes code to send anything to anyone.

## One graph per tab

Each tab has its own reactive graph and its own queue of work, as Solid has one per browser page. A shared store has a graph and a queue of its own, with no pages in it.

The reason for separate graphs is how Solid shows async work. Within one graph, a change and everything it reaches show together: a page waits until the slow loads the change set off have finished, then shows the new state in one go. With everyone in one graph, a slow load on Bob's page would hold Alice's page, and every user's rendering would run on one thread. With one graph per tab, Bob's slow load delays Bob.

## How a change reaches other pages

A shared store's methods run on the store's queue, one at a time, so `update(fn)` is atomic however many pages call it at once. When a method finishes, the store has a new revision: a number, and the values it changed. It publishes that revision to every graph that subscribed to the store.

A page's first read of `room.messages()` subscribes its graph. The store answers with its current values and their revision number in one step, and sends every later revision after that, so nothing posted in between is lost. The page's graph applies each revision as one write from outside (ADR 0014): its own computations run, and it renders its own pages on its own queue. A graph that finds a gap in the numbers, or a store that started over, asks for the current values again.

The values a store publishes are immutable once published. On one server, every graph reads the same objects and nothing is copied. Across servers, the same revisions travel as JSON. The developer's code is the same in both cases, and the in-process path uses the same subscription and the same revision numbers as the network, so nothing works on one server that would break on two.

A graph unsubscribes when its last reader of the store is gone.

## What other users see of a write

Other pages see what a store method finished, never what it is still doing. A click that calls `room.post(m)` runs the method on the store's queue as an action of the page that clicked: that page can show a guess right away (`optimistic`), sees `isPending` while it runs, and loses the guess if the method throws. Bob sees the message when the method has finished, and not before.

A method that throws publishes nothing. This differs from a Solid action, where plain writes stand when the action fails: a shared store's revision is all of the method's writes or none of them. A method that also saves to the database publishes after the save. Slow work, such as a call to another service, belongs before the method, so the store's queue only ever runs short steps.

A click that times out may still have succeeded. A method that can be retried takes a stable id from its caller, as the spike found: a guess is matched to the real message by that id, and a retry with the same id is recognised.

A `computed` or `query` in a shared store runs once for the store, not once per reader. While a query in the store is loading, each reader sees it pending beside its old value, and only that reader's own screen waits for it, according to that reader's own boundaries.

A shared store's code cannot read anything that differs between readers: `user`, `pathParam()`, a tab store. That keeps ADR 0030's rule for browser stores, now for every shared store. The runtime and the processor report it as an error.

## Permissions are what was rendered

A handler exists only for an element the page rendered (`Handlers`, ADR 0019), and a click on anything else is ignored. With every page rendered under its own user, `user.isAdmin() ? delButton() : null` is the permission check, and `delete` needs none of its own.

Rendering again is not enough when rights change, because the change and the click can arrive at different servers in either order. So a handler remembers which revisions the button depended on: the revisions of the shared values its element and every condition above it read when it rendered. A click on it reaches the store with those revisions, and the store runs the method only if none of them has changed since. If one has, the store refuses, the page renders again, and the user clicks the new button if there still is one. The developer writes the rule once, in the view.

Following from that:

- What a permission depends on must be a tracked value: `user.isAdmin()` reads a shared store of members or roles, not a cached boolean or an untracked service call. j2act cannot tell when an untracked answer is out of date. A protected handler whose element read nothing tracked is refused.
- A handler belongs to its target. When a row of a list now shows a different message, the row's handlers get new ids, so an old click on the delete button of message M never deletes message N that moved into its row. Today `Handlers` keeps an id while its element renders at the same position, and that has to change for keyed rows whatever this ADR decides.
- A revocation that the store orders before a click refuses the click. A click the store accepted first runs to its end. A method that must not finish once rights are gone checks again itself; rendering promises nothing about later.
- Which stores a page may open is also decided by the page: `shared(ChatRoom.class, roomId)` is called after whatever check the page makes, and a page that never opened the room has no handlers into it.

A revision is as fine as the reads. Shared stores are deep stores (ADR 0035), which track reads by property, and each property carries the revision that last changed it. A handler remembers the revisions of exactly the properties its element and the conditions above it read: the delete button of message M remembers M's owner, the reader's role and whatever else decided that it renders, not the whole list. A post that adds message N does not refuse a click on M's button; a change to M's owner or to the reader's role does.

## Keys and lifetime

A shared store is identified by its class and a key. The key is any value that serializes: a room id, a guild id, a shop id. With no key (`shared(Settings.class)`), there is one per application. Each time a store starts, it gets a new identity besides its key, so a delayed click meant for a room that ended never lands in a new room with the same key.

A store lives while any graph subscribes to it or any of its methods is running, plus a grace window, then ends. Its `retainedState` is saved as browser snapshots are (ADR 0026, 0030) and comes back when it opens again. Everything else is gone: shared state is live communication between people, and the database stays where data is kept. State that must not end when nobody is looking, such as an accepted payment, belongs in the database.

## Browser state becomes a shared store

`@BrowserScoped` is a shared store keyed by the browser cookie, with ADR 0030's identity rules. Tabs of one browser no longer share one graph (ADR 0033): each tab is its own graph reading the browser's stores like any other shared store. Two open tabs can show a change a moment apart when one of them waits on a slow load; in exchange, a slow load in one tab no longer holds the others, and there is one mechanism instead of two.

## Security

The graph is the boundary between users. A tab's graph holds one tab under one user: its component state, tab stores and field values, including what someone typed into a password field, live there and nowhere else. A shared store's graph holds no user at all, and its code cannot read `user`, a tab store or anything else that differs between readers. So a render can only ever run as the user of the tab it belongs to, and nothing private enters a shared store unless the application's own code writes it there. This is stricter than ADR 0033's one graph per browser, where code that runs later has to say which tab it belongs to (`Tracking.in`).

Logs and errors never print a store's values, as Retained State keeps snapshots out of logs.

## Load

Separate graphs keep users apart only if their work does not compete for the same threads. Loads that block get their own capacity, apart from the threads that run graphs and stores, so one user's slow database calls cannot starve everyone's rendering. A page whose socket is slow keeps a bounded queue: when it falls behind, j2act stops sending the steps in between and renders the latest state when it catches up.

## More than one server

The developer's code does not change. A shared store lives on one server. Another server whose pages read it subscribes once, however many of its users are watching, gets each revision once, and renders its own pages. A call to a store's method from another server is sent to the store's server, so its arguments must serialize. For a 20,000-user chat on 20 servers, one message is 20 updates between servers, then each server renders its own users.

Which server holds a store is decided by its key, with a lease in the app's DataSource that carries a number which goes up with every new holder. A save carries that number, so a holder that lost its lease cannot keep writing. While a store's server is gone, its readers show their last values as stale and its methods fail. Moving a live store between servers, and starting it elsewhere after a crash, are left for later.

## Rejected

- **One graph for every user,** or one per room with every viewer in it. A slow load anywhere would hold every page the same change reaches, everyone would share one thread, and a page reading ten rooms would need ten graphs at once.
- **Tabs joining the shared store's graph,** as tabs join their browser's graph today. A tab belongs to one graph, and a page reads many shared stores: several rooms, a guild, a browser.
- **A separate shared value with its own change tracking** (a `Shared<T>` beside Solid's signals). Deep stores would be implemented twice.
- **Other users seeing a write in progress,** as the spike's room-wide `isPending(messages)` did. One member's slow save becomes everyone's, and two saves at once cannot be told apart. A room that wants "Alice is typing" publishes it as a value of its own.
- **Checking permissions by rendering again before a click.** Correct on one server, wrong when the revocation and the click arrive at different servers.
- **Rendering a fragment once per server and sending the same HTML to every reader whose output would be identical.** j2act sees tracked reads only, so it cannot prove that a fragment read nothing private: a security context in a thread-local, a static or a service call is invisible to it, and one such read would send Alice's HTML to Bob. Most fragments also carry per-tab handler ids. And the saving is small, since a new message renders one row per reader, not the page.

## Considered for later

- **Changes from the database.** A store method saves in a transaction and records which key changed; servers reload what that key's pages read. Fits bookkeeping that the database already keeps correct, and writes from outside j2act. Needs an outbox so a crash between the commit and the notice loses nothing. Close to the reactive database on the backlog.
- **Data types that merge,** such as CRDTs for presence and collaborative text, where every server accepts writes. Each type needs its own rule for conflicts, and protected operations still need one place that orders them.

## Open

- How a deep store (ADR 0035) publishes its changes by property across graphs, so that editing one message re-renders one row per reader and a handler's revisions name properties.
- Ordering across stores: an action in one store that causes a write in another can reach a page in two updates. If guild membership decides who may delete in a channel, the click has to be checked where both are ordered.
- Sending less to pages that are hidden or not looking at a room, as Discord does for passive sessions.
- Heartbeats, and how long a reader shows stale data when a store's server stops answering.
- A load test of a large room: messages per second, open windows and latency, with real sockets, uneven permissions and slow readers.
