# Acknowledged server messages across reconnects

ADR 0010 resumes a tab whose socket comes back within the grace window "with State intact". State is intact; the page may not be. The server learns that a socket died some time after the network did, seconds or, on a dead Wi-Fi link, much longer, and what it writes meanwhile goes into a socket that accepts it and delivers nothing. A component's patch counts as sent, so it is not sent again after the reconnect, and the page shows old content there until that component changes once more. A client module's prop update is lost the same way, so the module shows stale data the server believes it has, and a lost client action or window() call times out after 30 seconds although nothing failed. Once the server does notice, `Tab.send` drops what it is given while there is no connection: a URL change from navigate(), a head update. ADR 0031 keeps effects running through a drop, which is exactly when writes pile up.

A message the client never receives is worse than one the server never receives. A user sees a click that did nothing and clicks again; the server has no way to know the page it is talking to has drifted. This ADR makes server-to-client messages reliable and leaves the other direction as it is.

## Numbered, acknowledged, resent

This is Blazor Server's scheme (`RemoteRenderer`), widened from render batches to every server message. Each message the server sends a tab carries a sequence number, and the server keeps it in the tab's buffer until the client acknowledges it. The client acknowledges after applying a message, and an acknowledgement covers every message up to its number, so acks may be batched. A tab whose socket is down still numbers and buffers what it would have sent; nothing is dropped for want of a connection. A numbered message never changes and never leaves the sequence except by an acknowledgement, so a resend has no gaps and the browser cannot have applied a different version of it.

The client's `hello` carries the highest number it applied. The server drops the buffered messages up to it and sends the rest in order, then carries on. A message the client already has is applied at most once: the client ignores numbers it has passed, so a resend after an ack that crossed the drop is harmless.

Client actions and window() calls are buffered like patches, so a call lost in flight is delivered on reconnect and runs exactly once. Its 30-second timeout counts from when it was delivered, not from when it was buffered behind a dead socket; a call still undelivered when the tab ends fails with a ClientException naming the disconnect, never silently.

A call can also run in the browser and lose its result on the way back. So the browser keeps each call's result, by call id, until the server acknowledges it, and its `hello` after a reconnect resends the unacknowledged ones; the server ignores a result for a call it already settled. A call the browser already ran is never run again for a resend, since its id is applied at most once. This is the one piece of reliability in the client-to-server direction, and only for call results.

## The buffer is bounded by backpressure

As Blazor does with `MaxBufferedUnacknowledgedRenderBatches`, the buffer has a cap (`withMaxUnacknowledged`, in bytes). When it is full the tab stops producing patches: components that change stay marked dirty instead of being rendered into the buffer. On reconnect the buffer is resent, and then the dirty components render once with their latest State, so a long drop costs one patch per changed component, not one per change. While the socket is known to be down or the buffer is full, a URL or head update does not take a number yet: it waits in a single slot per kind, where a newer update replaces it. It takes its number when any other message is admitted, and at reconnect after the buffered messages are resent, so collapsing only ever merges consecutive updates, never reorders them around a patch or a call, and never touches a message the browser may have applied. The numbered ones count against the cap and between them lie patches or calls, which are bounded themselves, so these cannot grow either. Client calls are counted against their own limit (`withMaxPendingClientCalls`, 256 by default); a call past it fails at once with a ClientException naming the limit, instead of piling up behind a socket that is gone. A buffered call that targets markup whose patch is being held back by the cap waits behind that patch, so it never reaches the browser before its element.

## Full re-render as the last resort

If the client finds a gap it cannot fill, such as a number missing in the middle of a resend or a patch whose anchor is not on the page, it asks for a full render, and the server renders the whole page and the client morphs it in, as LiveView does on every rejoin. This restores the page's markup; buffered client calls are still delivered from the buffer, so they are not lost to it. A reconnect after the tab is gone is a remount, as before (ADR 0010).

## Client to server

Apart from call results (above), events the client sends into a socket that has died are lost, as today; the runtime only queues what it sends while it knows the socket is down. The user sees the missed click and repeats it. Numbering that direction with the same scheme is possible later and needs no protocol change beyond an ack from the server. Uploads keep their own resumable chunks (ADR 0006).

## Before code

A test in Harness that drops the socket after a patch is written but before it is read, then reconnects, and checks that the page matches the server: first failing on today's code, then passing. The same for a client action, for a client action whose result is lost on the way back, for a navigate() while disconnected, and for several URL updates around a client call while disconnected, which must replay with no gap. The browser check gains a reconnect step that kills the socket at the network layer.

## Rejected

- A full re-render on every reconnect, LiveView's answer. Simple, and it heals markup, but a client call lost in flight stays lost, and a re-render of a large page on every blip costs more than resending a few patches.
- Buffering only patches, as Blazor does; Blazor does not buffer JS interop calls during a transient drop. Here client modules and window() are central (ADR 0022), so a lost call is the same drift as a lost patch.
- Reliable delivery in both directions now. The client side has a user watching it.

## Deviations from .NET

Blazor numbers and buffers render batches and pauses rendering when the buffer is full; this ADR does the same and adds client calls, URL and head messages to what is buffered, and a full re-render when the client finds a gap. The cap is in bytes rather than a batch count, since one patch can hold a whole page.
