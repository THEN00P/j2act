# Spike: one reactive owner per key

**Question.** Does ADR 0033's option A hold up? One owner per key anywhere in a cluster, so that state shared between people is reactive: a write is a signal write, saving is a step of it, readers on any server follow, and they can tell when their data is stale.

**What ran.** `SharedScopeSpikeTest` on the branch `shared-scope-spike`, thrown away after. Real j2act pages on the reader's side, a small stand-in for the owner (the port gives it real signals), two nodes in one JVM joined by real TCP sockets, and faked membership. 9 checks, 6 runs of the class, all passing after the fixes below.

```java
void post(String text) throws Exception {
  messages.update(list -> plus(list, text));   // a signal write, held until the action ends
  db.insert(key, text);                        // a step; if it throws, the write never happened
}
```

## What held

- A message reaches the other person with no database read. The database is read once, when the room wakes.
- While a save is slow, the other person's `isPending(messages)` is true beside the old list, and the new list and `pending:false` arrive in one patch.
- A failed save undoes the write. The writer's guess goes, and the other person never saw it.
- The room lives on one server. A person on another server reads and writes it the same way, with the same page code.
- 200 writes at once from two servers all land, in one order on both screens.
- A room ends with its last reader and reads the database again when it wakes.
- When the owner dies, the reader shows its list with `pending:true`, the room wakes on the survivor from the database in about 12 ms on loopback, and sending works again.

## What it corrected

- **A guess needs a key.** Showing a guess by appending gave the writer `hi,hi` for one update: the real message arrived while the guess was still applied. Waiting for the acknowledgement does not prevent this, because the frame and the end of the action are two updates. A guess that adds nothing once its key is in the list fixes it. Solid's docs say the same ("correlate by key").
- **A failed future does not mean it was not saved.** A write in flight when the owner dies reports failure to the writer, and in 4 of 6 runs its row was saved anyway. The screen still matched the database, since the room reloads it. A retry would duplicate the row, so a write that can be retried needs an idempotency key.

## What follows for the design

- Writes from another server go through the store's methods with serializable arguments. A lambda cannot travel, so `messages.update(fn)` runs only on the owner.
- Values cross as whole values here, so each frame copies the whole list. Long lists need deltas.
- Values must serialize. The spike used Java serialization; the real one should use the host's JSON codec, as Retained State does.

## What it did not test

- **Membership, and two owners for one key during a network split.** This is the hard part of option A, and the spike faked it with a shared list. An owner that cannot reach the others but can reach the database would keep accepting writes.
- **Silent failure.** The owner's sockets closed cleanly. A half-open connection needs heartbeats, and its detection time is the real length of the stale window.
- **Separate JVMs.** Both nodes shared a JVM, and killing one closed its sockets and threads.
- **The real owner.** The stand-in applies an action's writes at its end. The engine port decides the real behaviour.
- **Load.** One message with 200 in the list reached 1000 readers in about 20 ms, and 200 writes across two servers took about 180 ms, with an in-memory database and fake browser connections. These say the shape is not absurdly slow, and nothing more.
