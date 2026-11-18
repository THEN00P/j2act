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

  /**
   * Swaps HTML in for the element with its anchor, or for a region between its markers (ADR 0036), and sets an
   * attribute region's attributes. Defined once, right after the shell. The HTML comes as a string, not a template,
   * which the page's parser would read as HTML before it is known where it goes. j2f is morph.ts's parseIn: what goes
   * under an svg or math element is parsed in one, and in MathML's text elements in one of those, unless the parser
   * goes back to HTML there.
   */
  private static final String HELPER = "function j2f(h,p){var m=p&&p.namespaceURI,l=p&&p.localName,"
    + "w=m===\"http://www.w3.org/2000/svg\"?[\"svg\"]:m===\"http://www.w3.org/1998/Math/MathML\"?[\"math\"]:[];"
    + "if(w[0]===\"svg\"&&/^(foreignObject|desc|title)$/.test(l)||w[0]===\"math\"&&l===\"annotation-xml\""
    + "&&/^(text\\/html|application\\/xhtml\\+xml)$/i.test(p.getAttribute(\"encoding\")||\"\"))w=[];"
    + "else if(w[0]===\"math\"&&/^(mi|mo|mn|ms|mtext)$/.test(l))w.push(\"mi\");"
    + "var x=document.createElement(\"template\");x.innerHTML=w.map(function(n){return\"<\"+n+\">\"}).join(\"\")+h"
    + "+w.reverse().map(function(n){return\"</\"+n+\">\"}).join(\"\");if(!w.length)return x.content;"
    + "var f=document.createDocumentFragment(),o=x.content;for(var i=0;i<w.length;i++)o=o.firstChild;"
    + "while(o.firstChild)f.appendChild(o.firstChild);return f}"
    + "function j2s(a,h){var e=document.querySelector('[data-j2-scope=\"'+a+'\"]');"
    + "if(e){var c=j2f(h,e.parentNode).firstElementChild;if(c)e.replaceWith(c)}else{"
    + "var w=document.createTreeWalker(document.body,128),s=null,n;"
    + "while(n=w.nextNode()){if(n.data===\"j2:\"+a)s=n;else if(s&&n.data===\"/j2:\"+a){var r=document.createRange(),"
    + "f=j2f(h,s.parentNode);r.setStartBefore(s);r.setEndAfter(n);r.deleteContents();r.insertNode(f);break}}}"
    + "document.currentScript.remove()}"
    + "function j2a(a,v){var e=document.querySelector(\"[data-j2-a-\"+a+\"]\");if(e)for(var k in v)"
    + "v[k]===null?e.removeAttribute(k):e.setAttribute(k,v[k]);document.currentScript.remove()}"
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
    emit(scriptTag + "j2s(\"" + anchor + "\"," + scriptString(html) + ")</script>");
  }

  /** A JavaScript string of `s`, for inside a script (inScript). */
  static String scriptString(String s) {
    StringBuilder b = new StringBuilder(s.length() + 16);
    j2act.internal.json.Json.string(s, b);
    return inScript(b.toString());
  }

  /**
   * JSON as it can stand in an inline script: no `<` at all, so neither `</script` nor `<!--` changes how the page's
   * parser reads the script. Only a JSON string holds one, where `\u003c` reads the same.
   */
  static String inScript(String json) {
    return json.replace("<", "\\u003c");
  }

  /** Lane-only. An attribute region's attributes, as JSON (ADR 0036). */
  void patchAttributes(String anchor, String values) {
    emit(scriptTag + "j2a(\"" + anchor + "\"," + inScript(values) + ")</script>");
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
