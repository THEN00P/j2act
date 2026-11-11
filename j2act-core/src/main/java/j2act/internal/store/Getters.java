package j2act.internal.store;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * The property a method reference names (ADR 0035), read off the reference itself: a serializable one
 * says which method it stands for. A reference to a public getter names it under javac and under ECJ; a
 * lambda, or a reference the compiler had to compile into one, names only a synthetic method and is refused.
 */
public final class Getters {
  private Getters() {
  }

  /** The field a getter reference reads: getCity and isDone name city and done, and city() names city. */
  public static String property(Serializable getter) {
    SerializedLambda lambda;
    try {
      Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
      writeReplace.setAccessible(true);
      lambda = (SerializedLambda) writeReplace.invoke(getter);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IllegalArgumentException("at(...) takes a method reference to a getter, such as User::getName; this one"
        + " cannot be read: " + e, e);
    }
    String method = lambda.getImplMethodName();
    if (method.startsWith("lambda$")) {
      throw new IllegalArgumentException("at(...) takes a method reference to a public getter, such as User::getName,"
        + " not a lambda: a lambda names no property");
    }
    String owner = lambda.getImplClass().replace('/', '.');
    Class<?> type;
    try {
      type = Class.forName(owner, false, getter.getClass().getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("at(...): cannot find " + owner, e);
    }
    return field(type, method);
  }

  private static String field(Class<?> type, String method) {
    String bare = method.startsWith("get") && method.length() > 3 ? decapitalize(method.substring(3))
      : method.startsWith("is") && method.length() > 2 ? decapitalize(method.substring(2)) : method;
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (String candidate : new String[] {bare, method}) {
        try {
          c.getDeclaredField(candidate);
          return candidate;
        } catch (NoSuchFieldException e) {
          // the next candidate, or the superclass
        }
      }
    }
    throw new IllegalArgumentException(type.getName() + "." + method + "() has no field named " + bare
      + ": at(...) follows a getter to the field of its name");
  }

  private static String decapitalize(String name) {
    return name.length() > 1 && Character.isUpperCase(name.charAt(1)) ? name
      : Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }
}
