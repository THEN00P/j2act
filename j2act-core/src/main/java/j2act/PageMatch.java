package j2act;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/** A resolved route: how to build the page and the path parameters it matched. */
public final class PageMatch {

  private final Supplier<? extends LiveComponent> factory;
  private final Map<String, String> params;

  public PageMatch(Supplier<? extends LiveComponent> factory, Map<String, String> params) {
    this.factory = factory;
    this.params = Collections.unmodifiableMap(params);
  }

  LiveComponent create() {
    return factory.get();
  }

  Map<String, String> params() {
    return params;
  }
}
