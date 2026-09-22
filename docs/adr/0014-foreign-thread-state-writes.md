# Foreign-thread State writes

Server push is not a framework feature. The user's scheduler, message listener or Debezium consumer is the push source; J2ACT only makes it safe to call. State.set() from any thread is legal: it never mutates on the caller's thread, it enqueues onto the owning session's serial lane (ADR 0001), coalesces with other pending writes, and results in one re-render and one morph. Per-session single-threading therefore still holds: foreign writes are just more work items on the same lane. A read-modify-write from a foreign thread uses State.update(fn), which runs fn on the lane against the current value; get() followed by set() off-lane can lose updates, and dev mode warns on get() from a foreign thread.

Session isolation rests on four rules:

- Only server code holding a State reference can write it. The client never names a session or a State, only per-session handler ids (ADR 0013), so a crafted frame cannot reach a foreign writer's target.
- The write re-renders under the owning session's own AuthCtx and route, never the caller's. A listener cannot render one user's view with another user's identity.
- Ambient context (auth(), pathParam(), the current session) is bound per work item and cleared in a finally before the pool thread is reused. From a foreign thread it throws. Leaked ThreadLocals on pooled threads are the realistic cross-session takeover vector, so the runtime owns that cleanup and does not rely on the executor.
- A handle whose session is evicted or unmounted goes inert: set() is a no-op with a debug log. A reference held in a user registry cannot resurrect a session or keep it in memory.

Subscriptions live in an Effect: subscribe on mount, return the unsubscribe as cleanup. Unmount, navigation away and eviction (ADR 0010) run the cleanup. Deciding which sessions may receive which data (for example, a Debezium row broadcast only to its owner's States) is the user's authorization, as with any pub/sub. No pub/sub, topics or cluster bus in core.
