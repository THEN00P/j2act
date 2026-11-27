package j2act.internal.regions;

import j2act.DomContent;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What compiled code calls (ADR 0036): the build's enhancer passes each child expression of a view it cut out here,
 * as a supplier, and gets back what goes where the expression's value went. Inside a running view that is a region:
 * a part that runs again on its own when only what it read changed, and patches only itself. Anywhere else it is the
 * supplier's value. Public for the enhanced classes of an app, not API.
 */
public final class Regions {

  private static volatile Function<Supplier<? extends DomContent>, DomContent> factory;
  private static volatile BiFunction<Object[], Supplier<? extends DomContent>, DomContent> rows;
  private static volatile Attribute attributes;
  private static volatile Function<DomContent, DomContent> hoists;
  private static volatile Keyed keyed;

  /** The engine's side of each(items, key, row). */
  public interface Keyed {
    DomContent rows(java.util.Collection<?> items, Function<Object, Object> key, Function<Object, DomContent> row);
  }

  /**
   * What a row's bridge is (ADR 0036): the enhancer's lambda for a row of each(...), which returns its row as a region
   * of its own, kept while what it was given stays the very same.
   */
  public interface Bridged {
  }

  /** The engine's side of attr(...). */
  public interface Attribute {
    Object set(Object tag, Supplier<?> value, BiFunction<Object, Object, Object> setter);
  }

  static {
    try {
      // The engine's side installs itself when it loads.
      Class.forName("j2act.RegionTag", true, Regions.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("j2act-core is incomplete: no j2act.RegionTag", e);
    }
  }

  private Regions() {
  }

  /** Called once by the engine. */
  public static void install(Function<Supplier<? extends DomContent>, DomContent> make,
      BiFunction<Object[], Supplier<? extends DomContent>, DomContent> row, Attribute attribute,
      Function<DomContent, DomContent> hoist, Keyed keyedRows) {
    factory = make;
    keyed = keyedRows;
    rows = row;
    attributes = attribute;
    hoists = hoist;
  }

  /**
   * A constant part of a view (ADR 0036): built once by the enhanced class, which keeps what this returns, and rendered
   * once, its HTML written again on every later render. Only for what reads nothing and is never changed.
   */
  public static DomContent hoist(DomContent tree) {
    return hoists.apply(tree);
  }

  /**
   * each(items, key, row) in a running view (ADR 0036): a region of rows kept by their key, which runs again for a
   * change of the list alone, and then makes only the rows whose item is new, a change of membership or order going to
   * the page as list operations. Anywhere else, the rows, each keyed by its item's key.
   */
  @SuppressWarnings("unchecked")
  public static <T> DomContent keyed(java.util.Collection<T> items, Function<? super T, ?> key,
      Function<? super T, ? extends DomContent> row) {
    return keyed.rows(items, (Function<Object, Object>) key, (Function<Object, DomContent>) row);
  }

  /**
   * A row of each(...) in the running view (ADR 0036): a region as of(...) makes, but one that stays a region when it
   * reads nothing, so that a list of rows changes by moving, inserting and removing them, not by sending them all.
   */
  public static DomContent row(Supplier<? extends DomContent> content) {
    return rows.apply(null, content);
  }

  /**
   * A row of each(...), with what its row was given: the item, and what the row's lambda captured. When the list runs
   * again and gives a row the very same, that row is kept as it is, as Solid's mapArray keeps a row whose item is
   * still in the list, and neither runs nor renders again.
   */
  public static DomContent row(Object[] args, Supplier<? extends DomContent> content) {
    return rows.apply(args, content);
  }

  /**
   * An attribute's value in the running view (ADR 0036): `setter` is the call the view made with it, the tag's own
   * withX(...), which this makes now as the plain build did and again on its own whenever what the value read changes,
   * patching only the attributes it sets. Returns what that call returned.
   */
  public static Object attr(Object tag, Supplier<?> value, BiFunction<Object, Object, Object> setter) {
    return attributes.set(tag, value, setter);
  }

  /** This core's version, which an enhanced class must have been enhanced for. */
  public static final String VERSION = version();

  private static String version() {
    java.util.Properties p = new java.util.Properties();
    try (java.io.InputStream in = Regions.class.getResourceAsStream("version.properties")) {
      if (in != null) {
        p.load(in);
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return p.getProperty("version", "unknown");
  }

  /**
   * What an enhanced class runs as it loads: classes enhanced for another version of j2act call into this one in ways
   * it may not have, so they stop here with a clear error rather than somewhere in a render (ADR 0036).
   */
  public static String enhancedBy(String version) {
    if (!VERSION.equals(version)) {
      throw new IllegalStateException("this class was enhanced by j2act-enhancer " + version + ", but j2act-core is "
        + VERSION + ": build the app again with the j2act plugin of the same version as j2act-core");
    }
    return version;
  }

  /** A region of the running view, or the supplier's value outside one. */
  public static DomContent of(Supplier<? extends DomContent> content) {
    return factory.apply(content);
  }
}
