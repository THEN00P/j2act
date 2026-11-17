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
  private static volatile Function<Supplier<? extends DomContent>, DomContent> rows;
  private static volatile Attribute attributes;

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
      Function<Supplier<? extends DomContent>, DomContent> row, Attribute attribute) {
    factory = make;
    rows = row;
    attributes = attribute;
  }

  /**
   * A row of each(...) in the running view (ADR 0036): a region as of(...) makes, but one that stays a region when it
   * reads nothing, so that a list of rows changes by moving, inserting and removing them, not by sending them all.
   */
  public static DomContent row(Supplier<? extends DomContent> content) {
    return rows.apply(content);
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
