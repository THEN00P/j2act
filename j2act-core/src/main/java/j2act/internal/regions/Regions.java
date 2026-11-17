package j2act.internal.regions;

import j2act.DomContent;
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
  public static void install(Function<Supplier<? extends DomContent>, DomContent> make) {
    factory = make;
  }

  /** A region of the running view, or the supplier's value outside one. */
  public static DomContent of(Supplier<? extends DomContent> content) {
    return factory.apply(content);
  }
}
