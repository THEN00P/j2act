package j2act;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Dev mode's watch on class files while a debugger is attached (HotSwap). The debugger swaps
 * classes in the JVM without telling the app; a class file changing is the sign. The class folder
 * the app loads from counts at once. An editor's output that the app does not load from, such as
 * bin/main beside an exploded WAR, counts only when every class that changed there is in the
 * loaded folder too: code that runs into a class the class loader cannot find yet fails, and the
 * JVM keeps that failure for that reference until the class is swapped again.
 */
final class ClassWatch {

  /** What one poll saw. */
  static final class Change {
    static final Change NONE = new Change(false, null);
    /** Classes the app can load changed: re-render. */
    final boolean ready;
    /** A new class the loaded folder lacks, when one is waiting for the deployment. */
    final String waiting;

    Change(boolean ready, String waiting) {
      this.ready = ready;
      this.waiting = waiting;
    }
  }

  private final Path loaded;
  private final List<Path> editorOutputs = new ArrayList<>();
  private final Map<Path, Map<String, Long>> seen = new HashMap<>();
  private String lastWaiting;

  ClassWatch(Path loaded, List<Path> editorOutputs) {
    this.loaded = loaded;
    for (Path output : editorOutputs) {
      if (!same(output, loaded)) {
        this.editorOutputs.add(output);
      }
    }
    // The classes as they are at startup are what the JVM loaded: no change yet.
    poll();
  }

  Change poll() {
    boolean ready = !changed(loaded).isEmpty();
    String waiting = null;
    for (Path output : editorOutputs) {
      for (String cls : changed(output)) {
        if (Files.isRegularFile(loaded.resolve(cls))) {
          ready = true;
        } else if (waiting == null) {
          waiting = cls;
        }
      }
    }
    if (waiting != null) {
      // Wait for the deployment to copy it, which then counts as a change of the loaded folder.
      ready = false;
    }
    String report = waiting != null && !waiting.equals(lastWaiting) ? waiting : null;
    lastWaiting = waiting != null ? waiting : lastWaiting;
    if (!ready && report == null) {
      return Change.NONE;
    }
    return new Change(ready, report);
  }

  /** Class files under root, by relative path, that are new or modified since the last poll. */
  private List<String> changed(Path root) {
    Map<String, Long> now = new HashMap<>();
    if (Files.isDirectory(root)) {
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(p -> p.getFileName().toString().endsWith(".class")).forEach(p -> {
          try {
            now.put(root.relativize(p).toString().replace('\\', '/'), Files.getLastModifiedTime(p).toMillis());
          } catch (IOException e) {
            // Gone between listing and reading: the next poll sees it.
          }
        });
      } catch (IOException | java.io.UncheckedIOException e) {
        return List.of();
      }
    }
    Map<String, Long> before = seen.put(root, now);
    List<String> changed = new ArrayList<>();
    if (before == null) {
      return changed;
    }
    for (Map.Entry<String, Long> entry : now.entrySet()) {
      if (!entry.getValue().equals(before.get(entry.getKey()))) {
        changed.add(entry.getKey());
      }
    }
    return changed;
  }

  private static boolean same(Path a, Path b) {
    try {
      return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
    } catch (IOException e) {
      return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
  }
}
