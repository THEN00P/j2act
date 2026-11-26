# Shared state across users

> Proposed. Changes ADR 0030's "Browser state" and ADR 0033's "One `Runtime` per browser" and its first rejected alternative. Replaces the "one owner per key" direction at the end of ADR 0033 and in `docs/spikes/shared-scope.md`.

A chat room, a Discord-like server with its channels, a shop's live bookkeeping: state that many people look at and change at once. Today j2act can share state between the tabs of one browser and no further. This ADR lets any page read and write state that other users' pages also read, and keeps the code the developer writes the same as for state that belongs to one page:

```java
@SharedScoped
public class ChatRoom extends Store {
  private final Signal<List<Message>> messages = createSignal(List.of());

  public List<Message> messages() { return messages.get(); }
  public void post(Message m) { messages.update(list -> plus(list, m)); }
  public void delete(Message m) { messages.update(list -> minus(list, m)); }
}

// in a page
ChatRoom room = useSharedStore(ChatRoom.class, roomId);
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

A shared store runs on its own lane. A write from a page does not run there at once: the writes one job of the page makes to a store, such as everything one click handler wrote, go to the store together when the job ends, in their order, and the store applies them in one job of its own. An action's writes go when its body has ended well, and not at all if it failed. `update(fn)` runs `fn` on the store's lane, on what the store has then, so a read-modify-write is atomic however many pages write at once. Code on a page that reads the store reads what the page shows, so a decision a handler makes from what it read is made on what the user saw.

When a job of the store ends, the values it changed are a new revision: a number, and the values. It publishes that revision to every graph that subscribed to one of them.

A page's first read of `room.messages()` subscribes its graph. The store answers with its current values and their revision number in one step, and sends every later revision after that, so nothing posted in between is lost. The page's graph applies each revision in one job: its own computations run, and it renders its own pages on its own queue. A revision older than what a page has changes nothing there.

The values a store publishes are immutable once published: a value as it is, and a deep store (ADR 0035) as its snapshot, which is the same object for every part that did not change. On one server, every graph reads the same objects and nothing is copied. A page keeps its own copy of a deep store's nodes, merged from each snapshot by the rows' `id` field when their class has one and by position otherwise, so what read one row runs again only when that row changed. Across servers, the same revisions travel as JSON. The developer's code is the same in both cases, and the in-process path uses the same subscription and the same revision numbers as the network, so nothing works on one server that would break on two.

A graph unsubscribes when nothing in it reads the store's value any more: a value when the engine says its mirror lost its last reader, a deep store when the last computation that read its copy ran again without reading it or ended.

## What other users see of a write

Other pages see what a store applied, never what is still on its way. A click that runs an action writing `room.messages` shows on its own page as `isPending` until the action ends, and as a guess at once if the value is optimistic (`createOptimistic`, `createOptimisticStore`), which goes when the action ends. When the body ends well, its writes go to the store, the store applies and publishes them, and the page gets that revision, the end of `isPending` and the end of the action in one change. Bob sees the message when the store has applied it, and not before.

An action that throws sends nothing, and a handler that throws sends nothing either. This differs from a Solid action, where plain writes stand when the action fails: what a page sends a store is all of the writes of the job or the action, or none of them. A body that also saves to the database ends after the save, so the store has the write only once the database does.

A click that times out may still have succeeded. A method that can be retried takes a stable id from its caller, as the spike found: a guess is matched to the real message by that id, and a retry with the same id is recognised.

A `computed` or `query` in a shared store runs once for the store, not once per reader. While a query in the store is loading, each reader sees it pending beside its old value, and only that reader's own screen waits for it, according to that reader's own boundaries. A reset at an `errored(...)` on a page loads the store's failed value again, for every reader.

## Permissions are what was rendered

A handler exists only for an element the page rendered (`Handlers`, ADR 0019), and a click on anything else is ignored. With every page rendered under its own user, `user.isAdmin() ? delButton() : null` is the permission check, and `delete` needs none of its own.

Rendering again later is not enough when rights change, because the change can reach the store before the page hears of it, and the click can arrive in between. So every render notes what it read of other graphs and what it saw: a value as it was, and a deep store's part by its path from the store's root, with list rows named by their `id` field, or by position when their class has none. When a click arrives, j2act compares what its handler's region and the regions around it saw with the state the stores last published. If one of them changed, the page is behind: the click waits until the page has the revisions that changed it and has rendered again, and then runs if its element still renders, or is refused if it does not, as a click on a button that is gone. Events after a waiting click wait behind it, in order. The developer writes the rule once, in the view.

Following from that:

- What decides that an element shows is read in the regions around it, so those count. A region further out also reads the list it renders, its length and its other rows, which say nothing of a button in one row: only the reads that are not of a list's or a map's elements count from regions further out. A new message never makes a click on another message's button wait. In a build that is not enhanced, the whole view is one region, so a click waits whenever anything its view read changed, a few milliseconds for the page to catch up.
- What changed only through this page's own earlier writes does not count: a page that has not heard back of its own last click is not behind anyone, so a quick second click on "+1" runs at once. Each published state keeps who wrote it and the few states before.
- What a permission depends on must be a tracked value: `user.isAdmin()` reads a shared store of members or roles, not a cached boolean or an untracked service call. j2act cannot tell when an untracked answer is out of date.
- A handler belongs to its target. A row rendered with a key (`withKey`) has the key in its handlers' path, so when another message is shown there, its handlers get new ids, and an old click on the delete button of message M never deletes message N that moved into its row. A row with no key keeps its position's ids, as the processor's warning about missing keys says; the check above still waits for a page whose rows moved.
- A click the check let through, and the writes it makes, run to their end, also an action's, whose writes go to the store when its body ends. A revocation the store published before the click arrived makes the click wait and, without its button, refuses it. A method that must not finish once rights are gone checks again itself; rendering promises nothing about later.
- Which stores a page may open is also decided by the page: `useSharedStore(ChatRoom.class, roomId)` is called after whatever check the page makes, and a page that never opened the room has no handlers into it.

A check is as fine as the reads. With the enhancer (ADR 0036), each child expression is a region and each field read of a store row is its own: the delete button of message M depends on the reader's role, read in `user.isAdmin() ? delButton() : null`, and the edit button on M's owner, read in `user.isMessageOwner(message) ? editButton() : null`. A post that adds message N makes neither wait; a change to M's owner makes the edit button's click wait, and the reader's role the delete button's.

A click's writes to several stores land in all of them at once: a page's graph waits for each store's lane, in one order every lane keeps, and applies every batch before any store publishes. A store's own writes to other stores go as a job of their own to each.

## Keys and lifetime

A shared store is identified by its class and a key, and the class is annotated `@SharedScoped`. The key is any value with `equals` and `hashCode` that serializes: a room id, a guild id, a shop id. `useSharedStore(Settings.class)`, with no key, is the one store of the whole application. The store reads its key with `sharedKey()`, already in its field initializers, to load what it is for. It is no container bean, since a key is no context a container knows: j2act makes it with its no-argument constructor and injects its members, as Spring's `autowireBean` and CDI's injection target do. Code outside any tab, such as a message listener, reaches it with `j2act.sharedStore(ChatRoom.class, roomId)`. Each time a store starts, it gets a new identity besides its key, so a delayed click meant for a room that ended never lands in a new room with the same key.

What looked a store up holds it: a component until it leaves the page, other code of a tab until the tab ends, a store until it ends itself. A store also lives while any graph reads one of its values. When nothing holds or reads it any more, it waits the grace window a tab has, then saves its `retainedState` as browser snapshots are (ADR 0026, 0030) and ends; that comes back when the key is looked up again. Everything else is gone: shared state is live communication between people, and the database stays where data is kept. State that must not end when nobody is looking, such as an accepted payment, belongs in the database.

A shared store's code cannot use what differs between its readers: `auth()`, `pathParam()`, `queryParam()`, `useTab()`, a tab or browser store, `navigate()`. The runtime refuses each, and the processor reports it as an error at compile time, under javac and ECJ alike.

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
- **Checking permissions by rendering again before a click, with what the page has.** Wrong when the revocation reached the store and not yet the page: the store's published state decides whether the page is behind.
- **Refusing a click whose handler was rendered from anything that changed since.** A click on a button whose region read the message list would be refused whenever someone posted. Waiting for the page to catch up and checking that the element still renders refuses only what the user could no longer click.
- **Checking when the writes reach the store, and dropping them there.** The click's own handler has run by then, and an action's body, with its saves; and an action accepted at its click would fail at its end.
- **Rendering a fragment once per server and sending the same HTML to every reader whose output would be identical.** j2act sees tracked reads only, so it cannot prove that a fragment read nothing private: a security context in a thread-local, a static or a service call is invisible to it, and one such read would send Alice's HTML to Bob. Most fragments also carry per-tab handler ids. And the saving is small, since a new message renders one row per reader, not the page.

## Considered for later

- **Changes from the database.** A store method saves in a transaction and records which key changed; servers reload what that key's pages read. Fits bookkeeping that the database already keeps correct, and writes from outside j2act. Needs an outbox so a crash between the commit and the notice loses nothing. Close to the reactive database on the backlog.
- **Data types that merge,** such as CRDTs for presence and collaborative text, where every server accepts writes. Each type needs its own rule for conflicts, and protected operations still need one place that orders them.

## Open

- Ordering across stores: a store's own write to another store, such as a channel's effect writing the guild's unread count, reaches a page in two revisions, one per store. A click is checked against both stores' published states at once, and a page's writes to both land at once.
- Sending less to pages that are hidden or not looking at a room, as Discord does for passive sessions.
- Heartbeats, and how long a reader shows stale data when a store's server stops answering.
- A load test of a large room: messages per second, open windows and latency, with real sockets, uneven permissions and slow readers.
