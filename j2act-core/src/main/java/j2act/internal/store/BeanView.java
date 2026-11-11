package j2act.internal.store;

import j2act.internal.signals.StoreTarget;

/** The view of a bean. Application code never holds it: it gets a real instance of the class instead. */
public final class BeanView implements StoreView {
  final StoreTarget target;
  final Class<?> type;

  BeanView(StoreTarget target, Class<?> type) {
    this.target = target;
    this.type = type;
  }

  @Override public StoreTarget target() {
    return target;
  }
}
