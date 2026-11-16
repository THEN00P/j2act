package j2act.internal.names;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import j2act.ComponentTag;
import j2act.Store;

/**
 * The variable a createRetained call is assigned to (ADR 0031). Locals have no name at runtime,
 * so the processor writes a table per top-level class, and the call is found from the stack once
 * per value at setup. The key is the chain of names from setup() or a store's constructor down,
 * so a helper used twice gets two keys. Public only because other j2act modules need it; not API.
 */
public final class RetainedNames {

  private static final String DIRECTORY = "META-INF/j2act/names/";
  private static final Map<Class<?>, Map<Integer, String>> TABLES = new ConcurrentHashMap<>();
  /** The value's type as the source wrote it, per class and line, beside the name (ADR 0035). */
  private static final Map<Class<?>, Map<Integer, String>> TYPES = new ConcurrentHashMap<>();

  private RetainedNames() {
  }

  /**
   * The chain of variable names from setup() down to the caller of the code in skip, or null when
   * a frame on the way has none: no processor, no line numbers, a loop's lambda.
   */
  public static String chain(Class<?> skip) {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> {
      Deque<String> names = new ArrayDeque<>();
      Iterator<StackWalker.StackFrame> it = frames.iterator();
      while (it.hasNext()) {
        StackWalker.StackFrame frame = it.next();
        Class<?> declaring = frame.getDeclaringClass();
        if (declaring == RetainedNames.class || declaring == skip) {
          continue;
        }
        String name = frame.getLineNumber() < 0 ? null : table(declaring).get(frame.getLineNumber());
        if (name == null) {
          return null;
        }
        names.addFirst(name);
        if (isRoot(frame, declaring)) {
          return String.join("/", names);
        }
      }
      return null;
    });
  }

  private static boolean isRoot(StackWalker.StackFrame frame, Class<?> declaring) {
    return "setup".equals(frame.getMethodName()) && ComponentTag.class.isAssignableFrom(declaring)
      || "<init>".equals(frame.getMethodName()) && Store.class.isAssignableFrom(declaring);
  }

  /**
   * The type of the value the caller of the code in skip creates, as its source wrote it and the processor
   * recorded it: List<Todo> from Stored<List<Todo>> tags = ..., or from new ArrayList<Todo>() as the argument.
   * Null with no processor, or where the source does not write it, as var with a diamond.
   */
  public static java.lang.reflect.Type callSiteType(Class<?> skip) {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> {
      Iterator<StackWalker.StackFrame> it = frames.iterator();
      while (it.hasNext()) {
        StackWalker.StackFrame frame = it.next();
        Class<?> declaring = frame.getDeclaringClass();
        if (declaring == RetainedNames.class || declaring == skip) {
          continue;
        }
        table(declaring);
        String written = frame.getLineNumber() < 0 ? null
          : TYPES.getOrDefault(declaring, Collections.emptyMap()).get(frame.getLineNumber());
        return written == null ? null : Types.parse(written, declaring.getClassLoader());
      }
      return null;
    });
  }

  /** A debugger swapped classes: their line numbers changed, and so did their tables on disk. */
  public static void forget() {
    TABLES.clear();
    TYPES.clear();
  }

  private static Map<Integer, String> table(Class<?> type) {
    return TABLES.computeIfAbsent(type, RetainedNames::load);
  }

  private static Map<Integer, String> load(Class<?> type) {
    String binary = type.getName();
    int nested = binary.indexOf('$');
    String top = nested < 0 ? binary : binary.substring(0, nested);
    ClassLoader loader = type.getClassLoader();
    if (loader == null) {
      return Collections.emptyMap();
    }
    Map<Integer, String> lines = new HashMap<>();
    Map<Integer, String> types = new HashMap<>();
    TYPES.put(type, types);
    try (InputStream in = loader.getResourceAsStream(DIRECTORY + top + ".txt")) {
      if (in == null) {
        return Collections.emptyMap();
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        String[] parts = line.split(" ");
        if (parts.length >= 3 && parts[0].equals(binary)) {
          lines.put(Integer.valueOf(parts[1]), parts[2]);
          if (parts.length == 4) {
            types.put(Integer.valueOf(parts[1]), parts[3]);
          }
        }
      }
    } catch (IOException | NumberFormatException e) {
      return Collections.emptyMap();
    }
    return lines;
  }
}
