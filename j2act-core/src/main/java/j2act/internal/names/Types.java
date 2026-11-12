package j2act.internal.names;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A generic type the processor wrote out as text, java.util.List<com.example.Todo>, read back as a Type. A
 * name that no longer loads gives null, as after a class was renamed, and the caller falls back.
 */
final class Types {
  private final String text;
  private final ClassLoader loader;
  private int at;

  private Types(String text, ClassLoader loader) {
    this.text = text;
    this.loader = loader;
  }

  static Type parse(String text, ClassLoader loader) {
    try {
      Types parser = new Types(text, loader);
      Type type = parser.type();
      return parser.at == text.length() ? type : null;
    } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
      return null;
    }
  }

  private Type type() throws ClassNotFoundException {
    int start = at;
    while (at < text.length() && "<>,[]".indexOf(text.charAt(at)) < 0) {
      at++;
    }
    Class<?> raw = load(text.substring(start, at));
    Type type = raw;
    if (at < text.length() && text.charAt(at) == '<') {
      at++;
      List<Type> arguments = new ArrayList<>();
      while (text.charAt(at) != '>') {
        arguments.add(type());
        if (text.charAt(at) == ',') {
          at++;
        }
      }
      at++;
      type = parameterized(raw, arguments.toArray(new Type[0]));
    }
    while (at + 1 < text.length() && text.charAt(at) == '[' && text.charAt(at + 1) == ']') {
      at += 2;
      type = java.lang.reflect.Array.newInstance(raw, 0).getClass();
    }
    return type;
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    switch (name) {
      case "int": return int.class;
      case "long": return long.class;
      case "boolean": return boolean.class;
      case "double": return double.class;
      case "float": return float.class;
      case "short": return short.class;
      case "byte": return byte.class;
      case "char": return char.class;
      default: return Class.forName(name, false, loader);
    }
  }

  static ParameterizedType parameterized(Class<?> raw, Type... arguments) {
    return new ParameterizedType() {
      @Override public Type[] getActualTypeArguments() {
        return arguments.clone();
      }

      @Override public Type getRawType() {
        return raw;
      }

      @Override public Type getOwnerType() {
        return null;
      }

      @Override public String getTypeName() {
        StringBuilder name = new StringBuilder(raw.getName()).append('<');
        for (int i = 0; i < arguments.length; i++) {
          name.append(i == 0 ? "" : ", ").append(arguments[i].getTypeName());
        }
        return name.append('>').toString();
      }

      @Override public boolean equals(Object o) {
        return o instanceof ParameterizedType && raw.equals(((ParameterizedType) o).getRawType())
          && ((ParameterizedType) o).getOwnerType() == null
          && Arrays.equals(arguments, ((ParameterizedType) o).getActualTypeArguments());
      }

      @Override public int hashCode() {
        return raw.hashCode() ^ Arrays.hashCode(arguments);
      }

      @Override public String toString() {
        return getTypeName();
      }
    };
  }
}
