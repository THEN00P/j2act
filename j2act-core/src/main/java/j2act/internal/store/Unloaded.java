package j2act.internal.store;

import java.lang.reflect.Method;

/**
 * JPA state that is not loaded (ADR 0035): a lazy association or an entity's proxy that no query fetched.
 * A store never looks into one, since reading it would load it or fail, so it is one opaque value until the
 * application loads it. Asked of jakarta.persistence when that is on the classpath, by reflection, as core
 * depends on nothing.
 */
final class Unloaded {
  private static final Object UTIL;
  private static final Method IS_LOADED;

  static {
    Object util = null;
    Method isLoaded = null;
    try {
      Class<?> persistence = Class.forName("jakarta.persistence.Persistence", false, Unloaded.class.getClassLoader());
      util = persistence.getMethod("getPersistenceUtil").invoke(null);
      isLoaded = Class.forName("jakarta.persistence.PersistenceUtil", false, Unloaded.class.getClassLoader())
        .getMethod("isLoaded", Object.class);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      util = null;
    }
    UTIL = util;
    IS_LOADED = isLoaded;
  }

  private Unloaded() {
  }

  static boolean isLoaded(Object value) {
    if (UTIL == null || IS_LOADED == null) {
      return true;
    }
    try {
      return !Boolean.FALSE.equals(IS_LOADED.invoke(UTIL, value));
    } catch (ReflectiveOperationException | RuntimeException e) {
      return true;
    }
  }
}
