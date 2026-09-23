package j2act;

/** Which component classes override loading() or error(), cached per class. */
final class Boundaries {

  private static final ClassValue<Boolean> LOADING = new ClassValue<Boolean>() {
    @Override protected Boolean computeValue(Class<?> type) {
      return overrides(type, "loading");
    }
  };

  private static final ClassValue<Boolean> ERROR = new ClassValue<Boolean>() {
    @Override protected Boolean computeValue(Class<?> type) {
      return overrides(type, "error", PageError.class);
    }
  };

  private Boundaries() {
  }

  static boolean isLoading(Class<?> type) {
    return LOADING.get(type);
  }

  static boolean isError(Class<?> type) {
    return ERROR.get(type);
  }

  private static boolean overrides(Class<?> type, String name, Class<?>... params) {
    for (Class<?> c = type; c != null && c != ComponentTag.class && c != LiveComponent.class && c != Layout.class;
         c = c.getSuperclass()) {
      try {
        c.getDeclaredMethod(name, params);
        return true;
      } catch (NoSuchMethodException ignored) {
        // keep looking up the hierarchy
      }
    }
    return false;
  }
}
