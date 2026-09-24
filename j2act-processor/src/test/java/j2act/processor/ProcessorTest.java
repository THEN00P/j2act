package j2act.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import org.junit.jupiter.api.Test;

/**
 * ADR 0008's spike as a test: the same fixtures compiled by javac and by ECJ, with the
 * processor, must give the same diagnostics (message, severity, line, column, offset)
 * and the same generated files, and exactly the diagnostics each fixture marks with an
 * expect comment.
 */
class ProcessorTest {

  private static final Path FIXTURES = Paths.get("src/test/fixtures");
  private static final Pattern EXPECT = Pattern.compile("// expect( next line)?: (.*)$");
  private static final List<String> DIRECTORIES = Arrays.asList("fixtures", "clients");

  @Test void javacAndEcjAgree() throws IOException {
    for (String dir : DIRECTORIES) {
      Result javac = compile(ToolProvider.getSystemJavaCompiler(), sources(dir));
      Result ecj = compile(new EclipseCompiler(), sources(dir));
      assertFalse(javac.diagnostics.isEmpty(), dir);
      assertEquals(javac.diagnostics, ecj.diagnostics, "javac and ECJ disagree on " + dir);
      assertEquals(javac.generated, ecj.generated, "javac and ECJ generate different files for " + dir);
    }
  }

  @Test void reportsExactlyWhatTheFixturesExpect() throws IOException {
    for (String dir : DIRECTORIES) {
      List<Path> sources = sources(dir);
      for (JavaCompiler compiler : Arrays.asList(ToolProvider.getSystemJavaCompiler(), new EclipseCompiler())) {
        List<String> expected = new ArrayList<>();
        for (Path source : sources) {
          List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
          for (int i = 0; i < lines.size(); i++) {
            Matcher m = EXPECT.matcher(lines.get(i));
            if (m.find()) {
              expected.add(source.getFileName() + ":" + (m.group(1) == null ? i + 1 : i + 2) + ": " + m.group(2));
            }
          }
        }
        List<String> unmatched = new ArrayList<>();
        for (String diagnostic : compile(compiler, sources).diagnostics) {
          String[] parts = diagnostic.split(" ", 4);
          String line = parts[0].substring(0, parts[0].indexOf(':', parts[0].indexOf(':') + 1));
          boolean found = expected.removeIf(e -> e.startsWith(line + ": ") && parts[3].contains(e.substring(line.length() + 2)));
          if (!found) {
            unmatched.add(diagnostic);
          }
        }
        String name = compiler.getClass().getSimpleName() + " on " + dir;
        assertEquals(Collections.emptyList(), expected, name + " missed these");
        assertEquals(Collections.emptyList(), unmatched, name + " reported these unexpectedly");
      }
    }
  }

  @Test void clientTypesMatchTheCheckedInExample() throws IOException {
    Result javac = compile(ToolProvider.getSystemJavaCompiler(), sources("clients"));
    String expected = new String(Files.readAllBytes(FIXTURES.resolve("clients/Webcam.types.d.ts")), StandardCharsets.UTF_8);
    assertEquals(expected.replace("\r\n", "\n"), javac.generated.get("clients/Webcam.types.d.ts"));
  }

  @Test void jsonbWritesBytesAsNumbersAndDatesAsStrings() throws IOException {
    String jackson = compile(ToolProvider.getSystemJavaCompiler(), sources("clients")).generated.get("clients/Webcam.types.d.ts");
    String jsonb = compile(ToolProvider.getSystemJavaCompiler(), sources("clients"), "-Aj2act.json=jsonb")
      .generated.get("clients/Webcam.types.d.ts");
    assertTrue(jackson.contains("thumbnail: string,"), jackson);
    assertTrue(jsonb.contains("thumbnail: number[],"), jsonb);
  }

  @Test void aNonLayoutInLayoutPositionIsACompileErrorInBoth() throws IOException {
    List<Path> sources = sources("layout");
    for (JavaCompiler compiler : Arrays.asList(ToolProvider.getSystemJavaCompiler(), new EclipseCompiler())) {
      List<String> errors = compile(compiler, sources).diagnostics.stream()
        .filter(d -> d.contains(" ERROR "))
        .collect(Collectors.toList());
      assertFalse(errors.isEmpty(), compiler.getClass().getSimpleName() + " accepted it");
      assertTrue(errors.get(0).startsWith("Layouts.java:20:"), errors.toString());
    }
  }

  private static final class Result {
    /** "File.java:line:column@offset KIND message" for errors and this processor's diagnostics. */
    final List<String> diagnostics = new ArrayList<>();
    /** Generated files by path under the source output. */
    final Map<String, String> generated = new TreeMap<>();
  }

  private static Result compile(JavaCompiler compiler, List<Path> sources, String... extraOptions) throws IOException {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Path classes = Files.createTempDirectory("j2act-classes");
    Path generated = Files.createTempDirectory("j2act-generated");
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      List<String> options = new ArrayList<>(Arrays.asList("-d", classes.toString(), "-s", generated.toString(),
        "-classpath", classpath(), "--release", "17", "-encoding", "UTF-8"));
      options.addAll(Arrays.asList(extraOptions));
      JavaCompiler.CompilationTask task = compiler.getTask(new StringWriter(), files, diagnostics, options, null,
        files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).collect(Collectors.toList())));
      task.setProcessors(Collections.singletonList(new J2ActProcessor()));
      task.call();
    }
    Result result = new Result();
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      String message = d.getMessage(Locale.ROOT);
      boolean ours = message.startsWith("j2act:");
      if (d.getKind() == Diagnostic.Kind.ERROR || ours) {
        String file = d.getSource() == null ? "?" : Paths.get(d.getSource().toUri()).getFileName().toString();
        result.diagnostics.add(file + ":" + d.getLineNumber() + ":" + d.getColumnNumber() + "@" + d.getPosition()
          + " " + d.getKind() + " " + (ours ? message : "(compiler's own)"));
      }
    }
    Collections.sort(result.diagnostics);
    try (Stream<Path> walk = Files.walk(generated)) {
      for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
        result.generated.put(generated.relativize(file).toString().replace('\\', '/'),
          new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
      }
    }
    return result;
  }

  private static List<Path> sources(String dir) throws IOException {
    try (Stream<Path> walk = Files.walk(FIXTURES.resolve(dir))) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
    }
  }

  private static String classpath() {
    String surefire = System.getProperty("surefire.test.class.path");
    return surefire != null && !surefire.isEmpty() ? surefire : System.getProperty("java.class.path");
  }
}
