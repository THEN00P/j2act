package j2act;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The rest of a served page (ADR 0031): after the shell with its fallbacks, each loading(...)'s
 * HTML follows down the same response when ready, with a small script that swaps it in. It buffers
 * until the adapter attaches its sink, and ends when nothing is left to wait for or at the deadline;
 * what is still loading then arrives over the socket.
 */
final class PageStream {

  /** Swaps a template in for the element with its anchor. Defined once, right after the shell. */
  private static final String HELPER = "function j2s(a){var t=document.getElementById(\"j2t-\"+a),"
    + "e=document.querySelector('[data-j2-scope=\"'+a+'\"]');if(t){if(e&&t.content.firstElementChild)"
    + "e.replaceWith(t.content.firstElementChild);t.remove()}document.currentScript.remove()}"
    + "document.currentScript.remove()";

  private final Tab tab;
  private final String scriptTag;
  private final String tail;
  private final Deque<String> buffered = new ArrayDeque<>();
  private ServeResult.ChunkSink sink;
  private boolean ended;
  private boolean finished;
  private boolean done;
  private boolean draining;

  PageStream(Tab tab, String nonce, String tail) {
    this.tab = tab;
    this.scriptTag = nonce == null ? "<script>" : "<script nonce=\"" + Html.attributeValue(nonce) + "\">";
    this.tail = tail;
    buffered.add(scriptTag + HELPER + "</script>");
  }

  /** Lane-only. A boundary's HTML, ready to swap in. */
  void patch(String anchor, String html) {
    emit("<template id=\"j2t-" + anchor + "\">" + html + "</template>" + scriptTag + "j2s(\"" + anchor + "\")</script>");
  }

  /** Lane-only. The closing tags go last; nothing is written after them. */
  void end() {
    synchronized (this) {
      if (ended) {
        return;
      }
      ended = true;
      buffered.add(tail);
      finished = true;
      drain();
    }
  }

  boolean ended() {
    return ended;
  }

  private synchronized void emit(String chunk) {
    if (!ended) {
      buffered.add(chunk);
      drain();
    }
  }

  /** The adapter's sink, on the request thread: what is waiting goes out now. */
  synchronized void attach(ServeResult.ChunkSink next) {
    if (!done) {
      sink = next;
      drain();
    }
  }

  /** Writes on the executor, one drain at a time, so a slow client never holds up the tab's lane. */
  private void drain() {
    if (sink == null || draining || buffered.isEmpty() && !finished) {
      return;
    }
    draining = true;
    try {
      tab.engine.executor.execute(this::drainNow);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      draining = false;
      fail();
    }
  }

  private void drainNow() {
    while (true) {
      String chunk;
      ServeResult.ChunkSink target;
      boolean ending = false;
      synchronized (this) {
        target = sink;
        chunk = buffered.poll();
        if (target == null) {
          draining = false;
          return;
        }
        if (chunk == null) {
          draining = false;
          ending = finished;
          if (!ending) {
            return;
          }
          sink = null;
          done = true;
        }
      }
      try {
        if (ending) {
          target.end();
          return;
        }
        target.write(chunk);
      } catch (IOException | RuntimeException e) {
        fail();
        return;
      }
    }
  }

  /** The visitor left, or the response is gone: what is loading arrives over a socket, if one comes. */
  private void fail() {
    ServeResult.ChunkSink last;
    synchronized (this) {
      last = sink;
      sink = null;
      ended = true;
      done = true;
      draining = false;
      buffered.clear();
    }
    if (last != null) {
      try {
        last.end();
      } catch (RuntimeException ignored) {
        // Nothing more to do with this response.
      }
    }
    tab.post(tab::streamGone);
  }
}
