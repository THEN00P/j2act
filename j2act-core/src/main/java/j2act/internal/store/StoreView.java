package j2act.internal.store;

import j2act.internal.signals.StoreTarget;

/** What a store hands out for an object it holds: Solid's proxy. */
public interface StoreView {
  StoreTarget target();
}
