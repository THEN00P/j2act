package j2act.internal.signals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/** Runs bundled Solid test files, each in its own GraalJS context, as vitest gives each file its own module state. */
public final class Conformance {
  /** One test's outcome. */
  public static final class Result {
    public final String file;
    public final String name;
    public final String status;
    public final String message;

    Result(String file, String name, String status, String message) {
      this.file = file;
      this.name = name;
      this.status = status;
      this.message = message;
    }

    public String key() {
      return file + " :: " + name;
    }
  }

  private static final long FILE_BUDGET_SECONDS = Long.getLong("solid.budget", 120);

  private final Path bundles;
  private final Path module;
  private final Engine engine;
  private final Source vitestShim;
  private final Source solidShim;

  public Conformance(Path module) throws IOException {
    this.module = module;
    this.bundles = module.resolve("target/solid-bundles");
    this.engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
    this.vitestShim = source(module.resolve("js/vitest-shim.js"));
    this.solidShim = source(module.resolve("js/solid-shim.js"));
  }

  private static Source source(Path file) throws IOException {
    return Source.newBuilder("js", file.toFile()).build();
  }

  public List<Path> files(String filter) throws IOException {
    Pattern p = filter == null || filter.isEmpty() ? null : Pattern.compile(filter);
    List<Path> out = new ArrayList<>();
    try (var stream = Files.list(bundles)) {
      stream.filter(f -> f.getFileName().toString().endsWith(".js"))
        .filter(f -> p == null || p.matcher(name(f)).find())
        .sorted().forEach(out::add);
    }
    return out;
  }

  private static String name(Path bundle) {
    return bundle.getFileName().toString().replaceAll("\\.test\\.js$", "");
  }

  public List<Result> runAll(String filter) throws Exception {
    List<Path> files = files(filter);
    int threads = Math.max(1, Integer.getInteger("solid.threads", Math.max(1, java.lang.Runtime.getRuntime().availableProcessors() - 1)));
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<List<Result>>> futures = new ArrayList<>();
    for (Path f : files) {
      futures.add(pool.submit(() -> runFile(f)));
    }
    List<Result> all = new ArrayList<>();
    for (Future<List<Result>> f : futures) {
      all.addAll(f.get());
    }
    pool.shutdownNow();
    return all;
  }

  public List<Result> runFile(Path bundle) throws IOException {
    String file = name(bundle);
    List<Result> out = new ArrayList<>();
    Context context = Context.newBuilder("js").engine(engine).allowHostAccess(HostAccess.ALL)
      .allowHostClassLookup(c -> false).allowExperimentalOptions(true)
      .option("js.foreign-object-prototype", "true").build();
    Thread watchdog = new Thread(() -> {
      try {
        Thread.sleep(TimeUnit.SECONDS.toMillis(FILE_BUDGET_SECONDS));
        context.close(true);
      } catch (InterruptedException e) {
        // the file ended in time
      } catch (RuntimeException e) {
        // already closed
      }
    });
    watchdog.setDaemon(true);
    watchdog.start();
    try {
      context.enter();
      try {
        context.eval(vitestShim);
        Bridge bridge = new Bridge(context);
        context.getBindings("js").putMember("__bridge", bridge);
        context.eval(solidShim);
        String code = Files.readString(bundle, StandardCharsets.UTF_8);
        context.eval(Source.newBuilder("js", code, bundle.getFileName().toString()).build());
        Value bindings = context.getBindings("js");
        bindings.getMember("__runAll").execute();
        drain(context);
        Value finished = bindings.getMember("__finished");
        Value tick = bindings.getMember("__tick");
        while (!finished.execute().asBoolean()) {
          if (!tick.execute().asBoolean()) {
            drain(context);
            if (!finished.execute().asBoolean()) {
              out.add(new Result(file, "<file>", "failed", "hung: no timers left and the run never ended"));
            }
            break;
          }
          drain(context);
        }
        collect(file, bindings.getMember("__results").execute().asString(), out);
      } finally {
        context.leave();
      }
    } catch (PolyglotException e) {
      out.add(new Result(file, "<file>", "failed", e.isCancelled() ? "ran past its time budget" : String.valueOf(e.getMessage())));
    } catch (RuntimeException | Error e) {
      out.add(new Result(file, "<file>", "failed", String.valueOf(e)));
    } finally {
      watchdog.interrupt();
      try {
        context.close(true);
      } catch (RuntimeException e) {
        // already closed by the watchdog
      }
    }
    return out;
  }

  private static void drain(Context context) {
    context.eval("js", "0");
  }

  private static final Pattern RESULT = Pattern.compile(
    "\\{\"name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"status\":\"(\\w+)\"(?:,\"message\":\"((?:[^\"\\\\]|\\\\.)*)\")?\\}");

  private static void collect(String file, String json, List<Result> out) {
    Matcher m = RESULT.matcher(json);
    while (m.find()) {
      out.add(new Result(file, unescape(m.group(1)), m.group(2), m.group(3) == null ? null : unescape(m.group(3))));
    }
  }

  private static String unescape(String s) {
    return s.replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\");
  }

  /** For working on the port: Conformance [filter] prints what failed. */
  public static void main(String[] args) throws Exception {
    java.util.logging.LogManager.getLogManager().reset();
    Path module = Path.of(System.getProperty("solid.module", "j2act-signals-conformance"));
    String filter = args.length > 0 ? args[0] : "";
    boolean verbose = args.length > 1;
    Conformance c = new Conformance(module);
    long start = System.currentTimeMillis();
    List<Result> results = c.runAll(filter);
    Map<String, int[]> perFile = new TreeMap<>();
    int passed = 0;
    int failed = 0;
    int skipped = 0;
    for (Result r : results) {
      int[] counts = perFile.computeIfAbsent(r.file, k -> new int[3]);
      if ("passed".equals(r.status)) {
        passed++;
        counts[0]++;
      } else if ("failed".equals(r.status)) {
        failed++;
        counts[1]++;
        if (verbose || filter.length() > 0) {
          String msg = String.valueOf(r.message);
          System.out.println("FAIL " + r.key() + "\n     " + (msg.length() > 400 ? msg.substring(0, 400) + "..." : msg));
        }
      } else {
        skipped++;
        counts[2]++;
      }
    }
    if (System.getProperty("solid.report") != null) {
      List<String> lines = new ArrayList<>();
      for (Result r : results) {
        lines.add(r.status + "\t" + r.key() + ("failed".equals(r.status) ? "\t" + r.message : ""));
      }
      Files.write(Path.of(System.getProperty("solid.report")), lines, StandardCharsets.UTF_8);
    }
    if (filter.isEmpty()) {
      perFile.forEach((f, n) -> System.out.println(n[0] + "/" + (n[0] + n[1]) + "\t" + f));
    }
    System.out.println(passed + " passed, " + failed + " failed, " + skipped + " skipped, in "
      + (System.currentTimeMillis() - start) / 1000 + "s, " + perFile.size() + " files");
    System.exit(0);
  }
}
