package j2act;

import java.util.function.Supplier;

/**
 * One property of a store, from {@link Stored#at}: reading it subscribes to that property alone, and
 * writing it is a write to that property, with no draft. It binds a field:
 *
 * <pre>{@code
 * var city = user.at(User::getAddress, Address::getCity);
 * input().withValue(city.get()).onInput(e -> city.set(e.value()));
 * }</pre>
 */
public final class Part<U> implements Supplier<U> {
  private final Stored<?> store;
  private final String[] path;

  Part(Stored<?> store, String[] path) {
    this.store = store;
    this.path = path;
  }

  /** The property's value. Tracked, and only this property. */
  @Override
  @SuppressWarnings("unchecked")
  public U get() {
    return (U) store.readPath(path);
  }

  /** Writes the property. */
  public void set(U value) {
    store.writePath(path, value);
  }
}
