package j2act;

import java.lang.reflect.Method;

/**
 * What a client module's mount(...) returns: its props, for an element of type T
 * (ADR 0022). Pass it to withClient on that element: Mount&lt;VideoTag&gt; only fits a
 * video element, and the client's TS side receives an HTMLVideoElement.
 */
public final class Mount<T extends Tag<T>> {

  final ClientHandle handle;
  final Method method;
  final Object[] props;

  Mount(ClientHandle handle, Method method, Object[] props) {
    this.handle = handle;
    this.method = method;
    this.props = props;
  }
}
