package j2act;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dev mode (ADR 0024). On when the class output carries META-INF/j2act/dev.json (the annotation
 * processor writes it), the project folder it names exists on this machine, and the token is a
 * plain file rather than inside an archive. Then the app watches the Vite manifest and tells pages
 * to re-import what changed, and runs `vite build --watch` in the project, unless another app
 * already does. Off under test runners, and with -Dj2act.dev=false.
 */
final class DevMode implements AutoCloseable {

  private static final String[] TEST_RUNNERS = {
    "org.junit.", "org.testng.", "org.springframework.boot.test.", "io.cucumber.", "org.spockframework."
  };

  /** The runner's exit code when node_modules changed under it: start it again. */
  static final int RESTART = 75;
  /** Terminal colours in Vite's output, dropped from the log. */
  private static final String ANSI = "\u001B\\[[0-9;]*m";

  final File project;
  private J2Act engine;
  private String node;
  private Process vite;
  private FileLock lock;
  private FileChannel lockChannel;
  /** Package-private for the test that checks a closed DevMode leaves no hook behind. */
  Thread stopHook;
  private boolean closed;
  private int crashes;

  DevMode(File project) {
    this.project = project;
  }

  /** Dev mode for this engine, or null. */
  static DevMode detect(J2Act engine) {
    if ("false".equals(System.getProperty("j2act.dev"))) {
      return null;
    }
    URL token = engine.resourceLoader.getResource("META-INF/j2act/dev.json");
    if (token == null || !onDisk(token)) {
      return null;
    }
    if (underTest()) {
      return null;
    }
    String project;
    try (InputStream in = token.openStream()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> json = (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      project = (String) json.get("project");
    } catch (IOException | RuntimeException e) {
      return null;
    }
    File dir = project == null ? null : new File(project);
    if (dir == null || !new File(dir, "package.json").isFile()) {
      return null;
    }
    return new DevMode(dir);
  }

  /**
   * The token as a plain file: a class folder, or an exploded deployment such as the one an IDE
   * publishes to WildFly (vfs: URLs name the real path then). Inside a jar, a boot jar or a WAR
   * file it is not, wherever that archive runs, so a packaged app is never in dev mode.
   */
  static boolean onDisk(URL token) {
    if (!"file".equals(token.getProtocol()) && !"vfs".equals(token.getProtocol())) {
      return false;
    }
    try {
      return java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(new java.net.URI("file", null, token.getPath(), null)));
    } catch (java.net.URISyntaxException | RuntimeException e) {
      return false;
    }
  }

  private static boolean underTest() {
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      for (String runner : TEST_RUNNERS) {
        if (frame.getClassName().startsWith(runner)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Where @j2act/vite writes in this project: build/j2act for Gradle, target/classes for Maven. */
  java.nio.file.Path viteDir() {
    boolean gradle = new File(project, "build.gradle").isFile() || new File(project, "build.gradle.kts").isFile()
      || new File(project, "settings.gradle").isFile() || new File(project, "settings.gradle.kts").isFile();
    return project.toPath().resolve(gradle ? "build/j2act" : "target/classes").resolve(Modules.VITE_DIR);
  }

  /** Starts `vite build --watch` unless another process holds the project's watch lock. */
  void start(J2Act engine) {
    this.engine = engine;
    // @j2act/vite's runner ends with this JVM, since it watches its stdin pipe.
    File runner = runner();
    if (!runner.isFile()) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: " + runner + " is missing; run the build once"
        + " (gradle build, mvn package or npm install), then restart", null);
      return;
    }
    node = findNode();
    if (node == null) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: no Node found in the project or on the PATH;"
        + " serving the last build", null);
      return;
    }
    try {
      // Outside node_modules: npm ci deletes that folder, and on Windows a file this JVM holds
      // open stops it halfway, leaving a broken install behind.
      java.nio.file.Path lockFile = project.toPath().resolve(".j2act/dev.lock");
      java.nio.file.Files.createDirectories(lockFile.getParent());
      lockChannel = new RandomAccessFile(lockFile.toFile(), "rw").getChannel();
      lock = lockChannel.tryLock();
      if (lock == null) {
        engine.log(System.Logger.Level.INFO, "j2act dev: another process already watches " + project, null);
        lockChannel.close();
        lockChannel = null;
        return;
      }
    } catch (IOException e) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: could not start vite", e);
      return;
    }
    registerStopHook();
    launch();
  }

  private File runner() {
    return new File(project, "node_modules/@j2act/vite/dev.js");
  }

  /** Runs the runner, and runs it again when it exits while dev mode is on. */
  private synchronized void launch() {
    if (closed) {
      return;
    }
    Process process;
    try {
      // stdin stays a pipe the JVM holds open: the runner exits when it closes.
      process = new ProcessBuilder(node, runner().getAbsolutePath()).directory(project).redirectErrorStream(true).start();
    } catch (IOException e) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: could not start vite", e);
      return;
    }
    vite = process;
    long startedAt = System.currentTimeMillis();
    engine.log(System.Logger.Level.INFO, "j2act dev: vite build --watch in " + project, null);
    Thread pump = new Thread(() -> {
      try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line; (line = out.readLine()) != null; ) {
          engine.log(System.Logger.Level.INFO, "vite: " + line.replaceAll(ANSI, ""), null);
        }
      } catch (IOException e) {
        // The process ended.
      }
      exited(process, startedAt);
    }, "j2act-vite");
    pump.setDaemon(true);
    // A thread keeps its context class loader alive: never the application's, which a redeploy drops.
    pump.setContextClassLoader(null);
    pump.start();
  }

  /**
   * The runner ended while dev mode is on. It exits with RESTART when node_modules changed under it
   * (an npm install), and then starts again at once with a fresh Node; a crash starts it again after
   * a pause, until it keeps crashing.
   */
  private void exited(Process process, long startedAt) {
    int code;
    try {
      code = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    synchronized (this) {
      if (closed || vite != process) {
        return;
      }
      vite = null;
      if (code == RESTART) {
        engine.log(System.Logger.Level.INFO, "j2act dev: node_modules changed, starting vite again", null);
        crashes = 0;
      } else {
        crashes = System.currentTimeMillis() - startedAt < 30_000 ? crashes + 1 : 1;
        if (crashes > 3) {
          engine.log(System.Logger.Level.WARNING, "j2act dev: vite keeps exiting (code " + code + "); serving the last"
            + " build. Fix the error above, then redeploy.", null);
          return;
        }
        engine.log(System.Logger.Level.WARNING, "j2act dev: vite exited with code " + code + ", starting it again", null);
      }
    }
    try {
      Thread.sleep(code == RESTART ? 200 : 2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    launch();
  }

  /**
   * Stops the runner when the JVM exits without closing the engine. The hook is removed on close:
   * a registered hook stays reachable until the JVM exits, and through it this DevMode, its engine
   * and the class loader of an application that was redeployed long ago.
   */
  void registerStopHook() {
    Thread hook = new Thread(this::close, "j2act-vite-stop");
    hook.setContextClassLoader(null);
    Runtime.getRuntime().addShutdownHook(hook);
    stopHook = hook;
  }

  /** Node as the Gradle plugin, frontend-maven-plugin or the machine installed it. */
  private String findNode() {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    String exe = windows ? "node.exe" : "node";
    List<File> candidates = new ArrayList<>();
    File gradleNodes = new File(project, ".gradle/nodejs");
    File[] installs = gradleNodes.listFiles();
    if (installs != null) {
      for (File install : installs) {
        candidates.add(new File(install, windows ? exe : "bin/" + exe));
      }
    }
    candidates.add(new File(project, "node/" + exe));
    for (File candidate : candidates) {
      if (candidate.isFile()) {
        return candidate.getAbsolutePath();
      }
    }
    for (String dir : System.getenv().getOrDefault("PATH", System.getenv().getOrDefault("Path", "")).split(File.pathSeparator)) {
      File candidate = new File(dir, exe);
      if (candidate.isFile()) {
        return candidate.getAbsolutePath();
      }
    }
    return null;
  }

  @Override public synchronized void close() {
    closed = true;
    Thread hook = stopHook;
    stopHook = null;
    if (hook != null && Thread.currentThread() != hook) {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException e) {
        // The JVM is already shutting down.
      }
    }
    Process process = vite;
    vite = null;
    if (process != null) {
      process.descendants().forEach(ProcessHandle::destroy);
      process.destroy();
    }
    try {
      if (lock != null) {
        lock.release();
        lock = null;
      }
      if (lockChannel != null) {
        lockChannel.close();
        lockChannel = null;
      }
    } catch (IOException e) {
      // Released with the process anyway.
    }
  }
}
