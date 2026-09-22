# Session lifetime and reconnect

Session memory is the cost of serverful UI, so it gets hard bounds. A session is evicted after an idle timeout (no events), default 12h, configurable on the mount up to a 24h ceiling. On expiry the server closes the socket with an "expired" code and the client re-mounts on next interaction or visibility.

A dropped socket enters a short disconnected grace window, default 3 minutes like Blazor Server, configurable via withReconnectGrace(...). It covers network blips, laptop lid closes and proxy restarts, nothing more. Reconnecting inside it resumes the same session with State intact. The server cannot tell a closed tab from a dropped network, so the client also sends a pagehide beacon to release its session immediately (best effort), and each node caps retained disconnected sessions (withMaxDisconnected(...), oldest evicted first) so socket churn cannot pile up memory.

Reconnect after the session is gone (grace expired, evicted, other node, redeploy) re-mounts from the current URL over the new socket and morphs in place. No full page reload: mounting is cheap and a morph keeps scroll and focus. State is lost exactly as a browser reload would lose it, and that is by design, not a gap: session State is UI state, and anything worth keeping belongs in the user's database. A stale client runtime or CSRF token after a deploy triggers the same re-mount with a fresh token.

The host's auth session is independent and always wins: every reconnect and re-mount re-resolves AuthCtx through the identity seam, so a J2ACT session never outlives a logout.
