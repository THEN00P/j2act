package j2act.spring;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

import org.springframework.beans.factory.ListableBeanFactory;

import j2act.JsonBinding;

/**
 * The application's own Jackson mapper for client module values (ADR 0022), so they
 * serialize as its REST endpoints do. Reflection covers Jackson 2 (Boot 3) and Jackson 3
 * (Boot 4), whose mappers share these method names.
 */
final class JacksonBinding implements JsonBinding {

  private static final String[] MAPPERS = {"tools.jackson.databind.ObjectMapper", "com.fasterxml.jackson.databind.ObjectMapper"};

  private final Object mapper;
  private final Method write;
  private final Method typeFactory;
  private final Method constructType;
  private final Method read;

  private JacksonBinding(Object mapper) throws ReflectiveOperationException {
    this.mapper = mapper;
    this.write = mapper.getClass().getMethod("writeValueAsString", Object.class);
    this.typeFactory = mapper.getClass().getMethod("getTypeFactory");
    this.constructType = typeFactory.getReturnType().getMethod("constructType", Type.class);
    this.read = mapper.getClass().getMethod("readValue", String.class, constructType.getReturnType());
  }

  /** The context's mapper bean, Jackson 3 first; null when there is none. */
  static JsonBinding find(ListableBeanFactory beans, ClassLoader loader) {
    for (String name : MAPPERS) {
      try {
        Class<?> type = Class.forName(name, false, loader);
        Object mapper = beans.getBeanProvider(type).getIfAvailable();
        if (mapper != null) {
          return new JacksonBinding(mapper);
        }
      } catch (ReflectiveOperationException | LinkageError e) {
        // not this Jackson
      }
    }
    return null;
  }

  @Override public String write(Object value) {
    return (String) call(write, mapper, value);
  }

  @Override public Object read(String json, Type type) {
    Object javaType = call(constructType, call(typeFactory, mapper), type);
    return call(read, mapper, json, javaType);
  }

  private static Object call(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      throw cause instanceof RuntimeException ? (RuntimeException) cause
        : new IllegalArgumentException(cause.getMessage(), cause);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
