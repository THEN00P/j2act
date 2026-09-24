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
 * processor, must give the same diagnostics (message, severity, line, column, offset),
 * and exactly the ones each fixture marks with an expect comment.
 */
class ProcessorTest {

  private static final Path FIXTURES = Paths.get("src/test/fixtures");
  private static final Pattern EXPECT = Pattern.compile("// expect( next line)?: (.*)$");

  @Test void javacAndEcjAgree() throws IOException {
    List<Path> sources = sources("fixtures");
    List<String> javac = compile(ToolProvider.getSystemJavaCompiler(), sources);
    List<String> ecj = compile(new EclipseCompiler(), sources);
    assertFalse(javac.isEmpty());
    assertEquals(javac, ecj, "javac and ECJ disagree");
  }

  @Test void reportsExactlyWhatTheFixturesExpect() throws IOException {
    List<Path> sources = sources("fixtures");
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
      for (String diagnostic : compile(compiler, sources)) {
        String[] parts = diagnostic.split(" ", 4);
        String line = parts[0].substring(0, parts[0].indexOf(':', parts[0].indexOf(':') + 1));
        boolean found = expected.removeIf(e -> e.startsWith(line + ": ") && parts[3].contains(e.substring(line.length() + 2)));
        if (!found) {
          unmatched.add(diagnostic);
        }
      }
      String name = compiler.getClass().getSimpleName();
      assertEquals(Collections.emptyList(), expected, name + " missed these");
      assertEquals(Collections.emptyList(), unmatched, name + " reported these unexpectedly");
    }
  }

  @Test void aNonLayoutInLayoutPositionIsACompileErrorInBoth() throws IOException {
    List<Path> sources = sources("layout");
    for (JavaCompiler compiler : Arrays.asList(ToolProvider.getSystemJavaCompiler(), new EclipseCompiler())) {
      List<String> errors = compile(compiler, sources).stream()
        .filter(d -> d.contains(" ERROR "))
        .collect(Collectors.toList());
      assertFalse(errors.isEmpty(), compiler.getClass().getSimpleName() + " accepted it");
      assertTrue(errors.get(0).startsWith("Layouts.java:20:"), errors.toString());
    }
  }

  /** "File.java:line:column@offset KIND message" for errors and this processor's warnings. */
  private static List<String> compile(JavaCompiler compiler, List<Path> sources) throws IOException {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Path out = Files.createTempDirectory("j2act-processor");
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      List<String> options = Arrays.asList("-d", out.toString(), "-classpath", classpath(), "--release", "17",
        "-encoding", "UTF-8");
      JavaCompiler.CompilationTask task = compiler.getTask(new StringWriter(), files, diagnostics, options, null,
        files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).collect(Collectors.toList())));
      task.setProcessors(Collections.singletonList(new J2ActProcessor()));
      task.call();
    }
    List<String> out2 = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      String message = d.getMessage(Locale.ROOT);
      if (d.getKind() == Diagnostic.Kind.ERROR || message.startsWith("j2act:")) {
        String file = d.getSource() == null ? "?" : Paths.get(d.getSource().toUri()).getFileName().toString();
        out2.add(file + ":" + d.getLineNumber() + ":" + d.getColumnNumber() + "@" + d.getPosition()
          + " " + d.getKind() + " " + (d.getKind() == Diagnostic.Kind.ERROR ? "(compiler's own)" : message));
      }
    }
    Collections.sort(out2);
    return out2;
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
