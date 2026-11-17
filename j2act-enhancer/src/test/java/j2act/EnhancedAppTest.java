package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.enhancer.Enhancer;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * ADR 0036: an app's views compiled by javac and by ECJ, run plain and enhanced, show the same pages after every
 * step, and the enhanced one runs its view less.
 */
class EnhancedAppTest {

  private static final Path FIXTURES = Paths.get("src/test/fixtures/regions");

  @Test void javacClassesShowTheSamePagesEnhanced() throws Exception {
    check(ToolProvider.getSystemJavaCompiler());
  }

  @Test void ecjClassesShowTheSamePagesEnhanced() throws Exception {
    check(new EclipseCompiler());
  }

  private void check(JavaCompiler compiler) throws Exception {
    Path plain = Files.createTempDirectory("j2act-plain");
    Path enhanced = Files.createTempDirectory("j2act-enhanced");
    compile(compiler, plain);
    compile(compiler, enhanced);
    Enhancer.Report report = new Enhancer(classpath()).enhance(enhanced);
    assertTrue(report.regions >= 8, report.toString());
    assertTrue(report.uncut >= 1, "the child expression writing a local stays: " + report);
    verify(enhanced);
    // Again: what is stamped is left alone.
    assertEquals(0, new Enhancer(classpath()).enhance(enhanced).rewritten);

    Run before = run(plain);
    Run after = run(enhanced);
    assertEquals(before.pages, after.pages, compiler.getClass().getSimpleName());
    assertTrue(after.views < before.views, "the view ran " + after.views + " times enhanced, " + before.views + " plain");
    assertTrue(after.sent < before.sent, "patches carried " + after.sent + " chars enhanced, " + before.sent + " plain");
    assertTrue(after.raw.contains("<!--j2:"), after.raw);
    // Rows run on their own: selecting c patches the rows of a and c, and leaves b alone.
    String selecting = String.join("\n", after.selecting);
    assertTrue(selecting.contains("<li>a</li>") && selecting.contains("<li>c *</li>"), selecting);
    assertTrue(!selecting.contains("<li>b</li>") && !selecting.contains("<ul"), selecting);
  }

  @Test void javacStoreClassesTrackTheirReads() throws Exception {
    tracks(ToolProvider.getSystemJavaCompiler());
  }

  @Test void ecjStoreClassesTrackTheirReads() throws Exception {
    tracks(new EclipseCompiler());
  }

  /**
   * ADR 0035: a store's bean tracks its reads once enhanced, so a region reading its name runs again for a rename and
   * not for a move. Plain, the whole view runs for both; with regions alone, the region would read the whole bean.
   */
  private void tracks(JavaCompiler compiler) throws Exception {
    Path plain = Files.createTempDirectory("j2act-plain");
    Path enhanced = Files.createTempDirectory("j2act-enhanced");
    compile(compiler, plain);
    compile(compiler, enhanced);
    Enhancer.Report report = new Enhancer(classpath()).enhance(enhanced);
    assertEquals(1, report.tracked, "Profile, and none of the pages or components: " + report);
    verify(enhanced);
    List<String> steps = Arrays.asList("rename", "move", "move", "rename");
    int[] runs = new int[2];
    List<List<String>> pages = new ArrayList<>();
    for (Path classes : Arrays.asList(plain, enhanced)) {
      List<String> seen = new ArrayList<>();
      try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        EnhancedAppTest.class.getClassLoader())) {
        Class<?> page = loader.loadClass("fixtures.ProfilePage");
        try (Harness h = new Harness(() -> {
          try {
            return (LiveComponent) page.getConstructor().newInstance();
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
          }
        })) {
          h.load();
          h.connect();
          for (String step : steps) {
            h.click(Harness.clickOn(h.page(), step));
            seen.add(normalized(h.page()));
          }
        }
        runs[pages.size()] = page.getField("nameRuns").getInt(null);
        assertEquals(classes == enhanced, j2act.internal.store.Tracked.class.isAssignableFrom(loader.loadClass("fixtures.Profile")));
      }
      pages.add(seen);
    }
    assertEquals(pages.get(0), pages.get(1), compiler.getClass().getSimpleName());
    assertTrue(pages.get(1).get(3).contains("name Ada!!") && pages.get(1).get(3).contains("city Wien++"), pages.get(1).get(3));
    assertTrue(pages.get(1).get(3).contains("kept Wien++"), "an object kept from the view reads the store now: " + pages.get(1).get(3));
    assertEquals(5, runs[0], "plain, the whole view runs every time");
    assertEquals(3, runs[1], "enhanced, the name runs for itself and the two renames only");
  }

  private static final class Run {
    final List<String> pages = new ArrayList<>();
    /** What the first click on "select c" sent. */
    final List<String> selecting = new ArrayList<>();
    int views;
    long sent;
    String raw;
  }

  private static Run run(Path classes) throws Exception {
    Run run = new Run();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
      EnhancedAppTest.class.getClassLoader())) {
      Class<?> board = loader.loadClass("fixtures.Board");
      CompletableFuture<String> late = new CompletableFuture<>();
      board.getField("late").set(null, late);
      try (Harness h = new Harness(() -> {
        try {
          return (LiveComponent) board.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      })) {
        h.load();
        h.connect();
        run.pages.add(normalized(h.page()));
        for (String label : Arrays.asList("rename", "count 0", "toggle", "rename", "badge Ada!! 0", "count 1",
          "toggle", "LATE", "select c", "rename", "add d", "count 2", "select c")) {
          int from = h.conn.size();
          if (label.equals("LATE")) {
            late.complete("done");
            h.awaitPatch(from, html -> h.page().contains("slow done"));
          } else {
            h.click(Harness.clickOn(h.page(), label));
            if (label.equals("select c") && run.selecting.isEmpty()) {
              for (java.util.Map<String, String> m : h.conn.since(from, x -> "patch".equals(x.get("type")))) {
                run.selecting.add(m.get("html"));
              }
            }
          }
          run.pages.add(normalized(h.page()));
        }
        for (java.util.Map<String, String> m : h.conn.since(0, m -> "patch".equals(m.get("type")))) {
          run.sent += m.get("html").length();
        }
        run.raw = h.page();
      }
      Field views = board.getField("views");
      run.views = views.getInt(null);
    }
    return run;
  }

  /**
   * What a person sees: no markers, no handler ids, no tab ids. And no doctype or closing tags, which the served page
   * ends its stream with and a whole-page patch brings, so only the plain run's page model has them.
   */
  private static String normalized(String page) {
    return sortedAttributes(page.replace("<!DOCTYPE html>", "").replace("</body></html>", "").replaceAll("<!--/?j2:[^>]*-->", "")
      .replaceAll(" data-j2-[a-z0-9-]+=\"[^\"]*\"", "")
      .replaceAll(" data-j2-[a-z0-9-]+(?=[ >])", "")
      .replaceAll("<meta name=\"j2-[a-z]+\" content=\"[^\"]*\">", ""));
  }

  /** An attribute region's patch adds an attribute at the end, a whole render where the call put it: same element. */
  private static String sortedAttributes(String page) {
    java.util.regex.Matcher tag = java.util.regex.Pattern.compile("<([a-zA-Z0-9]+)((?: [^ >=]+(?:=\"[^\"]*\")?)+)>").matcher(page);
    StringBuffer out = new StringBuffer();
    while (tag.find()) {
      List<String> attrs = new ArrayList<>();
      java.util.regex.Matcher attr = java.util.regex.Pattern.compile(" ([^ >=]+(?:=\"[^\"]*\")?)").matcher(tag.group(2));
      while (attr.find()) {
        attrs.add(attr.group(1));
      }
      java.util.Collections.sort(attrs);
      tag.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("<" + tag.group(1) + " " + String.join(" ", attrs) + ">"));
    }
    tag.appendTail(out);
    return out.toString();
  }

  private static void compile(JavaCompiler compiler, Path out) throws IOException {
    List<File> sources;
    try (Stream<Path> walk = Files.walk(FIXTURES)) {
      sources = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).collect(Collectors.toList());
    }
    StringWriter errors = new StringWriter();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      boolean ok = compiler.getTask(errors, files, null,
        Arrays.asList("-d", out.toString(), "-classpath", System.getProperty("java.class.path"), "--release", "11",
          "-proc:none", "-g"),
        null, files.getJavaFileObjectsFromFiles(sources)).call();
      assertTrue(ok, errors.toString());
    }
  }

  private static List<Path> classpath() {
    List<Path> paths = new ArrayList<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      paths.add(Paths.get(entry));
    }
    return paths;
  }

  /** ASM's own check of every rewritten class, as the JVM's verifier would see it. */
  private static void verify(Path classes) throws Exception {
    try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
      EnhancedAppTest.class.getClassLoader()); Stream<Path> walk = Files.walk(classes)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())) {
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(Files.readAllBytes(file)), loader, false, new PrintWriter(problems));
        assertEquals("", problems.toString(), file.toString());
      }
    }
  }
}
