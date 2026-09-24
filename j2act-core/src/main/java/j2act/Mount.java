package j2act;

/**
 * What a client module's mount(...) returns: its props, for an element of type T
 * (ADR 0022). Mount&lt;VideoTag&gt; means the client attaches only to a video element, and
 * its TS side receives an HTMLVideoElement.
 */
public final class Mount<T extends Tag<T>> {

  Mount() {
  }
}
