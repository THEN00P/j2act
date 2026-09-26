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
 * SPIKE: dev mode. On when the class output carries META-INF/j2act/dev.json (the annotation
 * processor writes it), the project folder it names exists on this machine, and the token
 * is a plain file rather than inside a jar. Then the app watches the Vite manifest and tells
 * pages to re-import what changed, and runs `vite build --watch` in the project, unless
 * another app already does. Off under test runners, and with -Dj2act.dev=false.
 */
final class DevMode implements AutoCloseable {

  private static final String[] TEST_RUNNERS = {
    "org.junit.", "org.testng.", "org.springframework.boot.test.", "io.cucumber.", "org.spockframework."
  };

  final File project;
  private Process vite;
  private FileLock lock;
  private FileChannel lockChannel;

  private DevMode(File project) {
    this.project = project;
  }

  /** Dev mode for this engine, or null. */
  static DevMode detect(J2Act engine) {
    if ("false".equals(System.getProperty("j2act.dev"))) {
      return null;
    }
    URL token = engine.resourceLoader.getResource("META-INF/j2act/dev.json");
    if (token == null || !"file".equals(token.getProtocol()) && !"vfs".equals(token.getProtocol())) {
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
    // @j2act/vite's runner ends with this JVM, since it watches its stdin pipe.
    File runner = new File(project, "node_modules/@j2act/vite/dev.js");
    if (!runner.isFile()) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: " + runner + " is missing; run the build once"
        + " (gradle build, mvn package or npm install), then restart", null);
      return;
    }
    String node = findNode();
    if (node == null) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: no Node found in the project or on the PATH;"
        + " serving the last build", null);
      return;
    }
    try {
      File lockFile = new File(project, "node_modules/.j2act-dev.lock");
      lockChannel = new RandomAccessFile(lockFile, "rw").getChannel();
      lock = lockChannel.tryLock();
      if (lock == null) {
        engine.log(System.Logger.Level.INFO, "j2act dev: another process already watches " + project, null);
        lockChannel.close();
        return;
      }
      List<String> command = new ArrayList<>(List.of(node, runner.getAbsolutePath()));
      // stdin stays a pipe the JVM holds open: the runner exits when it closes.
      vite = new ProcessBuilder(command).directory(project).redirectErrorStream(true).start();
      engine.log(System.Logger.Level.INFO, "j2act dev: vite build --watch in " + project, null);
      Thread pump = new Thread(() -> {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(vite.getInputStream(), StandardCharsets.UTF_8))) {
          for (String line; (line = out.readLine()) != null; ) {
            engine.log(System.Logger.Level.INFO, "vite: " + line.replaceAll("\u001B\\[[0-9;]*m", ""), null);
          }
        } catch (IOException e) {
          // The process ended.
        }
      }, "j2act-vite");
      pump.setDaemon(true);
      pump.start();
      Runtime.getRuntime().addShutdownHook(new Thread(this::close, "j2act-vite-stop"));
    } catch (IOException e) {
      engine.log(System.Logger.Level.WARNING, "j2act dev: could not start vite", e);
    }
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
