package j2act;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A value kept in the page's query string (ADR 0030), as nuqs's useQueryState and Livewire's
 * #[Url] keep theirs: it survives a reload, belongs to its tab, follows the back button and can
 * be shared. Reads are tracked like queryParam(). A write replaces the URL by a soft navigation
 * after the current event, so the browser's history gains no entry; the initial value leaves
 * the parameter out.
 *
 * <pre>{@code
 * private final QueryState<String> filter = queryState("q", "");
 * input().withValue(filter.get()).onChange(e -> filter.set(e.value()))
 * }</pre>
 */
public final class QueryState<T> extends Primitive implements Supplier<T> {

  private final String name;
  private final T initial;
  private final Function<String, T> parse;
  private final Function<? super T, String> format;

  QueryState(String name, T initial, Function<String, T> parse, Function<? super T, String> format) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("a query state needs a parameter name");
    }
    this.name = name;
    this.initial = initial;
    this.parse = Objects.requireNonNull(parse, "parse");
    this.format = Objects.requireNonNull(format, "format");
  }

  /** The value read when the parameter is absent. */
  T initial() {
    return initial;
  }

  /** The parsing for initial's type: String, Integer, Long, Double, Boolean or an enum. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static <T> QueryState<T> of(String name, T initial) {
    Objects.requireNonNull(initial, "initial: give queryState a value of the parameter's type, or a parser");
    Class<?> type = initial.getClass();
    Function<String, Object> parse;
    if (type == String.class) {
      parse = s -> s;
    } else if (type == Integer.class) {
      parse = Integer::valueOf;
    } else if (type == Long.class) {
      parse = Long::valueOf;
    } else if (type == Double.class) {
      parse = Double::valueOf;
    } else if (type == Boolean.class) {
      parse = s -> "true".equals(s) ? Boolean.TRUE : "false".equals(s) ? Boolean.FALSE : null;
    } else if (initial instanceof Enum) {
      Class<? extends Enum> enumType = ((Enum<?>) initial).getDeclaringClass();
      parse = s -> Enum.valueOf(enumType, s);
    } else {
      throw new IllegalArgumentException("queryState has no parser for " + type.getName()
        + "; pass one: queryState(name, initial, parse, format)");
    }
    Function<Object, String> format = initial instanceof Enum ? v -> ((Enum<?>) v).name() : String::valueOf;
    return new QueryState<>(name, initial, (Function<String, T>) (Function) parse, (Function<T, String>) (Function) format);
  }

  /** The parameter's value, or the initial value when the URL lacks it or it does not parse. Tracked. */
  @Override public T get() {
    if (cell == null) {
      return initial;
    }
    return valueIn((RouteInfo) cell.owner.routeCell().read());
  }

  /** Replaces the parameter after the current event; the initial value or null removes it. */
  public void set(T value) {
    Cell bound = requireCell();
    Tab tab = bound.tab;
    tab.post(() -> write(bound, value));
  }

  /** Read-modify-write on the lane, against the URL as it is then. */
  public void update(UnaryOperator<T> fn) {
    Cell bound = requireCell();
    Tab tab = bound.tab;
    tab.post(() -> write(bound, fn.apply(valueIn((RouteInfo) bound.owner.routeCell().peek()))));
  }

  private T valueIn(RouteInfo route) {
    String raw = route.queryParams.get(name);
    if (raw == null) {
      return initial;
    }
    try {
      T parsed = parse.apply(raw);
      return parsed == null ? initial : parsed;
    } catch (RuntimeException e) {
      return initial;
    }
  }

  private void write(Cell bound, T value) {
    if (bound.disposed || bound.owner == null || bound.owner.disposed) {
      return;
    }
    RouteInfo route = (RouteInfo) bound.owner.routeCell().peek();
    String next = route.withQueryParam(name, value == null || value.equals(initial) ? null : format.apply(value));
    if (!next.equals(route.url())) {
      bound.tab.navigate(next, "replace");
    }
  }

  @Override Cell createCell(Tab tab, String address) {
    return new Slot(tab, address);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof Slot;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }

  /** Holds the slot; the value itself lives in the tab's route. */
  static final class Slot extends Cell {
    Slot(Tab tab, String address) {
      super(tab, address);
    }

    @Override Object peek() {
      return null;
    }
  }
}
