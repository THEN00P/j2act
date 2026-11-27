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
    assertTrue(report.hoisted >= 1, "span(\"static\") is built once: " + report);
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
    assertEquals(3, report.tracked, "Profile, Saved and Line, and not Pet, nor any page or component: " + report);
    verify(enhanced);
    List<String> steps = Arrays.asList("rename", "move", "move", "rename", "pet");
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
    assertTrue(pages.get(1).get(4).contains("pet Tom#"),
      "a kept object reads a nested one that is still equal to what it was: " + pages.get(1).get(4));
    assertEquals(6, runs[0], "plain, the whole view runs every time");
    assertEquals(3, runs[1], "enhanced, the name runs for itself and the two renames only");
  }

  @Test void javacShapesShowTheSamePagesEnhanced() throws Exception {
    shapes(ToolProvider.getSystemJavaCompiler());
  }

  @Test void ecjShapesShowTheSamePagesEnhanced() throws Exception {
    shapes(new EclipseCompiler());
  }

  /** Shapes' pages, plain and enhanced: the same pages after every step, and none the browser would parse amiss. */
  private void shapes(JavaCompiler compiler) throws Exception {
    Path plain = Files.createTempDirectory("j2act-plain");
    Path enhanced = Files.createTempDirectory("j2act-enhanced");
    compile(compiler, plain);
    compile(compiler, enhanced);
    new Enhancer(classpath()).enhance(enhanced);
    verify(enhanced);
    for (String name : Arrays.asList("TemplateAttr", "HtmlWith", "RawText", "Revealed")) {
      List<List<String>> pages = new ArrayList<>();
      for (Path classes : Arrays.asList(plain, enhanced)) {
        List<String> seen = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
          EnhancedAppTest.class.getClassLoader())) {
          Class<?> shapes = loader.loadClass("fixtures.Shapes");
          CompletableFuture<String> first = new CompletableFuture<>();
          CompletableFuture<String> second = new CompletableFuture<>();
          shapes.getField("first").set(null, first);
          shapes.getField("second").set(null, second);
          Class<?> page = loader.loadClass("fixtures.Shapes$" + name);
          try (Harness h = new Harness(() -> {
            try {
              return (LiveComponent) page.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
              throw new IllegalStateException(e);
            }
          })) {
            String served = h.load();
            if (classes == enhanced) {
              assertEquals(null, BrowserParse.problems(served), name + ": " + served);
            }
            seen.add(normalized(served));
            h.connect();
            Tab tab = h.engine.tab(h.sid);
            for (String step : name.equals("Revealed") ? Arrays.asList("second", "first") : Arrays.asList("more", "more")) {
              int from = h.conn.size();
              if (step.equals("second")) {
                second.complete("B");
                tab.call(() -> null).get();
              } else if (step.equals("first")) {
                first.complete("A");
                h.awaitPatch(from, html -> h.page().contains("one:A") && h.page().contains("two:B"));
              } else {
                h.click(Harness.clickOn(h.page(), step));
              }
              seen.add(normalized(h.page()));
            }
          }
        }
        pages.add(seen);
      }
      assertEquals(pages.get(0), pages.get(1), name + ", " + compiler.getClass().getSimpleName());
    }
  }

  @Test void javacClassesKeepTheirSerialVersion() throws Exception {
    serialVersions(ToolProvider.getSystemJavaCompiler());
  }

  @Test void ecjClassesKeepTheirSerialVersion() throws Exception {
    serialVersions(new EclipseCompiler());
  }

  /** A Serializable class the enhancer rewrites reads back what the plain app saved: its serialVersionUID stays. */
  private void serialVersions(JavaCompiler compiler) throws Exception {
    Path plain = Files.createTempDirectory("j2act-plain");
    Path enhanced = Files.createTempDirectory("j2act-enhanced");
    compile(compiler, plain);
    compile(compiler, enhanced);
    new Enhancer(classpath()).enhance(enhanced);
    verify(enhanced);
    for (String name : Arrays.asList("fixtures.Saved", "fixtures.Saved$Page")) {
      long[] uids = new long[2];
      int i = 0;
      for (Path classes : Arrays.asList(plain, enhanced)) {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
          EnhancedAppTest.class.getClassLoader())) {
          Class<?> type = loader.loadClass(name);
          uids[i++] = java.io.ObjectStreamClass.lookup(type).getSerialVersionUID();
          if (classes == enhanced) {
            assertTrue(type.getName().endsWith("Page") || j2act.internal.store.Tracked.class.isAssignableFrom(type),
              name + " is tracked");
          }
        }
      }
      assertEquals(uids[0], uids[1], name + ", " + compiler.getClass().getSimpleName());
    }
  }

  @Test void javacRoomPagesKeepTheirSizeAsLinesComeAndGo() throws Exception {
    keepsSize(ToolProvider.getSystemJavaCompiler());
  }

  @Test void ecjRoomPagesKeepTheirSizeAsLinesComeAndGo() throws Exception {
    keepsSize(new EclipseCompiler());
  }

  /**
   * ADR 0036, 0037: a page of a shared room whose lines come and go stays the size of what it shows, enhanced. Found
   * by tools/chat-load.mjs: each line that ever was kept its rows' nodes (a slot made as if the list's first node ran),
   * its records (a hand-out's walk left a witness on each) and what the rows read (a region's reads went to its
   * creator, which never ran again).
   */
  private void keepsSize(JavaCompiler compiler) throws Exception {
    Path enhanced = Files.createTempDirectory("j2act-enhanced");
    compile(compiler, enhanced);
    new Enhancer(classpath()).enhance(enhanced);
    try (URLClassLoader loader = new URLClassLoader(new URL[] {enhanced.toUri().toURL()},
      EnhancedAppTest.class.getClassLoader())) {
      Class<?> page = loader.loadClass("fixtures.RoomPage");
      @SuppressWarnings("unchecked")
      Class<? extends Store> roomType = (Class<? extends Store>) loader.loadClass("fixtures.Room");
      try (Harness h = new Harness(() -> {
        try {
          return (LiveComponent) page.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      })) {
        h.load();
        h.connect();
        Object room = h.engine.sharedStore(roomType, "room");
        java.lang.reflect.Method post = roomType.getMethod("post", String.class, String.class);
        java.lang.reflect.Method drop = roomType.getMethod("dropOldestUnpinned");
        int[][] sizes = new int[2][];
        List<java.lang.ref.WeakReference<Object>> firstRows = new ArrayList<>();
        int n = 0;
        for (int round = 0; round < 2; round++) {
          for (int i = 0; i < 100; i++) {
            // Writes from outside a tab land in their order, later: the test counts the lines itself.
            post.invoke(room, i % 2 == 0 ? "ada" : "bob", "m" + (++n));
            if (n > 5) {
              drop.invoke(room);
            }
            // One line at a time, as people post: writes from outside a tab that come together land together.
            String shown = ": m" + n + "<";
            Harness.eventually(() -> h.page().contains(shown), "line " + n + " shows");
          }
          String last = ": m" + n + "<";
          String gone = ": m" + (n - 4) + "<";
          // m1 stays pinned, with the last four.
          // The four last lines, and the one before them gone: the page as it stays until the next line.
          Harness.eventually(() -> h.page().contains(last) && !h.page().contains(gone), "the last four lines show");
          Tab tab = h.engine.liveTab(h.token);
          int[] now = new int[3];
          tab.host.hop(() -> {
            try {
              now[0] = owned(tab.rootOwner);
              now[1] = liveRecords(tab.host.mirrors);
              now[2] = reads(tab.root);
            } catch (ReflectiveOperationException e) {
              throw new IllegalStateException(e);
            }
          });
          sizes[round] = now;
          if (round == 1) {
            // Each row runs as if under the list's node now, never one that went: that would keep it, and with it
            // every node that went after it, as JFR found on the chat demo.
            int[] stale = new int[1];
            tab.host.hop(() -> {
              try {
                for (Scope row : listScope(tab.root).children.values()) {
                  Object under = field(row.slot, "parentComputed");
                  if (under instanceof j2act.internal.signals.Owner
                      && tab.rt.isDisposed((j2act.internal.signals.Owner) under)) {
                    stale[0]++;
                  }
                }
              } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
              }
            });
            assertEquals(0, stale[0], "rows running under a node that went");
          }
          if (round == 0) {
            tab.host.hop(() -> {
              Scope list = listScope(tab.root);
              for (Scope row : list.children.values()) {
                if (!(row.html != null && row.html.contains(": m1<"))) {
                  cuts(row, firstRows);
                }
              }
              // And the rows its last run made, which the rows' scopes took the nodes of.
              Object made = ((CutTag) list.instance).current();
              if (made instanceof Fragment) {
                List<DomContent> rows = ((Fragment) made).children;
                for (DomContent row : rows.subList(Math.min(1, rows.size()), rows.size())) {
                  firstRows.add(new java.lang.ref.WeakReference<>(row));
                }
              }
            });
          }
        }
        // Every line of the first round is gone: so are its rows' regions, once nothing holds them.
        int kept = firstRows.size();
        for (int i = 0; i < 20 && kept > 0; i++) {
          System.gc();
          Thread.sleep(50);
          kept = 0;
          for (java.lang.ref.WeakReference<Object> ref : firstRows) {
            kept += ref.get() == null ? 0 : 1;
          }
        }
        assertEquals(0, kept, kept + " of the first round's " + firstRows.size() + " regions are still held");
        // A line's row runs when it comes, and is kept while its line is in the room (ADR 0036): not once per change.
        int rows = page.getField("rows").getInt(null);
        assertTrue(rows <= 2 * n, rows + " row runs for " + n + " lines");
        String what = "owners, records, reads after 100 lines and after 200: " + Arrays.toString(sizes[0]) + " "
          + Arrays.toString(sizes[1]);
        assertTrue(sizes[1][0] <= sizes[0][0] + 4, what);
        assertTrue(sizes[1][1] <= sizes[0][1] + 2, what);
        assertTrue(sizes[1][2] <= sizes[0][2] + 8, what);
      }
    }
  }

  /** The region that shows the lines: the one whose scope has a row per line. */
  private static Scope listScope(Scope scope) {
    if (scope.instance instanceof CutTag && scope.children.size() >= 4) {
      return scope;
    }
    for (Scope child : scope.children.values()) {
      Scope found = listScope(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The regions showing now, weakly. */
  private static void cuts(Scope scope, List<java.lang.ref.WeakReference<Object>> into) {
    if (scope.instance instanceof CutTag) {
      into.add(new java.lang.ref.WeakReference<>(scope.instance));
    }
    for (Scope child : scope.children.values()) {
      cuts(child, into);
    }
  }

  private static Object field(Object of, String name) throws ReflectiveOperationException {
    for (Class<?> c = of.getClass(); c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(of);
      } catch (NoSuchFieldException e) {
        // up
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static int owned(Object owner) throws ReflectiveOperationException {
    int n = 1;
    for (Object child = field(owner, "firstChild"); child != null; child = field(child, "nextSibling")) {
      n += owned(child);
    }
    return n;
  }

  /** The records of the page's copies of other graphs' stores that are still live. */
  private static int liveRecords(Mirrors mirrors) throws ReflectiveOperationException {
    int n = 0;
    for (Object root : ((java.util.Map<?, ?>) field(mirrors, "byRoot")).keySet()) {
      Object family = field(root, "fam");
      n += ((java.util.Set<?>) field(family, "live")).size();
    }
    return n;
  }

  private static int reads(Scope scope) throws ReflectiveOperationException {
    int n = scope.sharedReads().size();
    Object cut = field(scope, "cutReads");
    if (cut != null) {
      for (Object list : ((java.util.Map<?, ?>) cut).values()) {
        n += ((List<?>) list).size();
      }
    }
    for (Scope child : scope.children.values()) {
      n += reads(child);
    }
    return n;
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
