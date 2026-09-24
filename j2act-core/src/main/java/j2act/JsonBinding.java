package j2act;

import java.lang.reflect.Type;

/**
 * How client module values cross the wire (ADR 0022): mount props, action arguments and
 * results, callback arguments. It is the host's own JSON library, so values serialize as
 * they do in the application's REST endpoints: the Spring adapter binds the application's
 * Jackson mapper and the Jakarta adapter binds JSON-B. The default handles strings,
 * numbers, booleans, enums, lists, arrays and string-keyed maps, and fails loudly on the rest.
 */
public interface JsonBinding {

  String write(Object value);

  /** A value of the given type, e.g. Size.class or a List&lt;Size&gt; ParameterizedType. */
  Object read(String json, Type type);

  /** The dependency-free default. */
  static JsonBinding basic() {
    return BasicJsonBinding.INSTANCE;
  }
}
