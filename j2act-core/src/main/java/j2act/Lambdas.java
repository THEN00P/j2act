package j2act;

import j2act.internal.json.Json;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lambda that goes to another server and runs there (ADR 0037): Signal.update, Stored.update and onStore take
 * serializable lambdas, which travel as the class and the method that make them, with what they capture in JSON. Only
 * what can travel safely travels: a value the JSON codec carries by its class, such as a String, a number, an enum, a
 * record or a bean, and a shared store, which is the store itself over there. Anything else, a component, a list, a
 * service, is refused where the lambda is made, with what it captured. The receiving server runs only application
 * code, of a store's, a component's or an allowed package's class, from the same build, which the class's bytes tell.
 */
final class Lambdas {

  private Lambdas() {
  }

  /** Why a lambda cannot go to another server, said to whoever wrote it. */
  static final class Unsupported extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    Unsupported(String message) {
      super(message);
    }
  }

  /** The lambda as JSON, to run on another server. */
  static Map<String, Object> encode(Object fn, J2Act engine) {
    SerializedLambda lambda = serialized(fn);
    Map<String, Object> out = new LinkedHashMap<>();
    String capturing = lambda.getCapturingClass().replace('/', '.');
    out.put("capturing", capturing);
    out.put("iface", lambda.getFunctionalInterfaceClass());
    out.put("ifaceMethod", lambda.getFunctionalInterfaceMethodName());
    out.put("ifaceSig", lambda.getFunctionalInterfaceMethodSignature());
    out.put("kind", (long) lambda.getImplMethodKind());
    out.put("implClass", lambda.getImplClass());
    out.put("implMethod", lambda.getImplMethodName());
    out.put("implSig", lambda.getImplMethodSignature());
    out.put("instSig", lambda.getInstantiatedMethodType());
    out.put("build", build(load(capturing, engine)));
    List<Object> args = new ArrayList<>();
    for (int i = 0; i < lambda.getCapturedArgCount(); i++) {
      args.add(capture(lambda.getCapturedArg(i), lambda, i, engine));
    }
    out.put("args", args);
    return out;
  }

  /** A lambda another server sent, made again here, after checking that it may run here. */
  @SuppressWarnings("unchecked")
  static Object decode(Map<String, Object> in, J2Act engine, List<String> allowed) {
    String capturing = (String) in.get("capturing");
    Class<?> type = load(capturing, engine);
    requireApplicationCode(type, allowed);
    String implClass = ((String) in.get("implClass")).replace('/', '.');
    if (!implClass.equals(capturing)) {
      requireApplicationCode(load(implClass, engine), allowed);
    }
    if (!build(type).equals(in.get("build"))) {
      throw new IllegalStateException("a lambda of " + capturing + " came from another build of the application: every"
        + " server of a cluster has to run the same one (ADR 0037)");
    }
    List<Object> args = (List<Object>) in.get("args");
    Object[] captured = new Object[args.size()];
    for (int i = 0; i < captured.length; i++) {
      captured[i] = uncapture((Map<String, Object>) args.get(i), engine);
    }
    SerializedLambda lambda = new SerializedLambda(type, (String) in.get("iface"), (String) in.get("ifaceMethod"),
      (String) in.get("ifaceSig"), ((Number) in.get("kind")).intValue(), (String) in.get("implClass"),
      (String) in.get("implMethod"), (String) in.get("implSig"), (String) in.get("instSig"), captured);
    try {
      Method make = type.getDeclaredMethod("$deserializeLambda$", SerializedLambda.class);
      make.setAccessible(true);
      return make.invoke(null, lambda);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("a lambda of " + capturing + " could not be made here (ADR 0037)", e);
    }
  }

  private static SerializedLambda serialized(Object fn) {
    if (!(fn instanceof java.io.Serializable)) {
      throw new Unsupported("a write to a shared store on another server takes a lambda written in place, such as"
        + " update(n -> n + 1), not one kept in a variable of a plain functional type (ADR 0037): " + fn.getClass());
    }
    try {
      Method replace = fn.getClass().getDeclaredMethod("writeReplace");
      replace.setAccessible(true);
      Object out = replace.invoke(fn);
      if (out instanceof SerializedLambda) {
        return (SerializedLambda) out;
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      // not a lambda: said below
    }
    throw new Unsupported("only a lambda or a method reference can go to a shared store on another server (ADR 0037),"
      + " not a " + fn.getClass().getName());
  }

  private static Object capture(Object value, SerializedLambda lambda, int index, J2Act engine) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (value == null) {
      out.put("kind", "null");
      return out;
    }
    if (value instanceof Store) {
      SharedHub hub = ((Store) value).sharedHub();
      if (hub == null) {
        throw unsupported(lambda, index, value, "a store that is not @SharedScoped, which another server does not have");
      }
      out.put("kind", "store");
      out.put("type", hub.type.getName());
      out.put("key", hub.key == Store.APPLICATION ? null : engine.json.write(hub.key));
      out.put("keyType", hub.key == Store.APPLICATION ? null : hub.key.getClass().getName());
      return out;
    }
    if (value instanceof java.util.Collection || value instanceof Map || value.getClass().isArray()) {
      throw unsupported(lambda, index, value, "a collection, whose elements' types JSON does not carry; capture a"
        + " record or a bean that holds it");
    }
    if (value instanceof ComponentTag || value instanceof Primitive || value instanceof Tab
        || value instanceof Runnable || value.getClass().isSynthetic()) {
      throw unsupported(lambda, index, value, "something that lives on this server only; capture the values it holds");
    }
    String json;
    try {
      json = engine.json.write(value);
      engine.json.read(json, value.getClass());
    } catch (RuntimeException e) {
      throw unsupported(lambda, index, value, "a value the JSON codec cannot write and read back: " + e.getMessage());
    }
    out.put("kind", "value");
    out.put("type", value.getClass().getName());
    out.put("json", json);
    return out;
  }

  private static Unsupported unsupported(SerializedLambda lambda, int index, Object value, String why) {
    return new Unsupported("the lambda " + lambda.getImplMethodName() + " in " + lambda.getCapturingClass().replace('/', '.')
      + " captures a " + value.getClass().getName() + " (its value #" + index + "), " + why + ", so it cannot go to a"
      + " shared store on another server (ADR 0037)");
  }

  private static Object uncapture(Map<String, Object> in, J2Act engine) {
    String kind = (String) in.get("kind");
    if ("null".equals(kind)) {
      return null;
    }
    if ("store".equals(kind)) {
      @SuppressWarnings("unchecked")
      Class<? extends Store> type = (Class<? extends Store>) load((String) in.get("type"), engine);
      String key = (String) in.get("key");
      Object k = key == null ? null : engine.json.read(key, load((String) in.get("keyType"), engine));
      return engine.sharedStore(type, k);
    }
    Class<?> type = load((String) in.get("type"), engine);
    return engine.json.read((String) in.get("json"), type);
  }

  /** Code other servers may run here: the application's stores and components, and the packages allowed. */
  private static void requireApplicationCode(Class<?> type, List<String> allowed) {
    String name = type.getName();
    if (type.getClassLoader() == null) {
      throw new SecurityException(name + " is the JDK's, not application code, which alone another server may run here"
        + " (ADR 0037)");
    }
    for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
      if ((Store.class.isAssignableFrom(c) || ComponentTag.class.isAssignableFrom(c)) && !isFramework(c)) {
        return;
      }
    }
    for (String prefix : allowed) {
      if (name.startsWith(prefix.endsWith(".") ? prefix : prefix + ".")) {
        return;
      }
    }
    throw new SecurityException(name + " is neither a store nor a component, nor in a package allowed with"
      + " Cluster.Builder.allowRemoteCode(...), so another server may not run its code here (ADR 0037)");
  }

  /** One of j2act's own classes, from the jar j2act itself comes from. */
  private static boolean isFramework(Class<?> type) {
    java.security.CodeSource mine = J2Act.class.getProtectionDomain().getCodeSource();
    java.security.CodeSource its = type.getProtectionDomain().getCodeSource();
    return mine != null && its != null && mine.getLocation() != null && mine.getLocation().equals(its.getLocation());
  }

  private static Class<?> load(String name, J2Act engine) {
    try {
      return Class.forName(name, false, engine.resourceLoader);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(name + " is not on this server: every server of a cluster runs the same build"
        + " (ADR 0037)", e);
    }
  }

  private static final Map<Class<?>, String> BUILDS = new ConcurrentHashMap<>();

  /** What tells one build of a class from another: a hash of its bytes. */
  static String build(Class<?> type) {
    return BUILDS.computeIfAbsent(type, t -> {
      String resource = t.getName().replace('.', '/') + ".class";
      ClassLoader loader = t.getClassLoader() != null ? t.getClassLoader() : ClassLoader.getSystemClassLoader();
      try (java.io.InputStream in = loader.getResourceAsStream(resource)) {
        if (in == null) {
          return "unknown";
        }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
          digest.update(buffer, 0, n);
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
      } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
        return "unknown";
      }
    });
  }

  /** For tests: the JSON a lambda would travel as. */
  static String json(Object fn, J2Act engine) {
    StringBuilder b = new StringBuilder();
    Json.write(encode(fn, engine), b);
    return b.toString();
  }
}
