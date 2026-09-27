package dev.j2act.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * SPIKE: builds the client modules and page entries with Vite into
 * target/classes. In Eclipse's incremental builds it runs only when a frontend file changed,
 * as told by m2e's BuildContext, and refreshes its output so Eclipse sees and publishes it.
 * Node comes from frontend-maven-plugin's install (project/node) or the PATH.
 */
@Mojo(name = "bundle", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class BundleMojo extends AbstractMojo {

  static final String[] FRONTEND_SOURCES =
    {"**/*.ts", "**/*.tsx", "**/*.mts", "**/*.js", "**/*.jsx", "**/*.mjs", "**/*.cjs", "**/*.css"};
  static final String[] CONFIG = {"package.json", "package-lock.json", "pnpm-lock.yaml", "vite.config.ts",
    "vite.config.js", "vite.config.mjs", "tsconfig.json"};

  @Parameter(defaultValue = "${project.basedir}", readonly = true)
  private File basedir;

  @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
  private File outputDirectory;

  @Parameter(property = "j2act.skip", defaultValue = "false")
  private boolean skip;

  @Component
  private BuildContext buildContext;

  @Override public void execute() throws MojoExecutionException {
    if (skip || !new File(basedir, "package.json").isFile()) {
      return;
    }
    File output = new File(outputDirectory, "META-INF/j2act/vite");
    // An incremental build runs Vite when a frontend file changed, or when no build is there at
    // all: a failed or cleaned-away build must not stay missing until the next edit.
    if (buildContext.isIncremental() && !changed() && new File(output, ".vite/manifest.json").isFile()) {
      getLog().debug("j2act: no frontend file changed");
      return;
    }
    // vite build: type errors are the typecheck goal's, not the build's. Once more on failure,
    // since an IDE clean can pull the folder away from under a build.
    try {
      exec(basedir, List.of("vite", "build"));
    } catch (MojoExecutionException first) {
      getLog().warn("j2act: vite build failed, trying once more: " + first.getMessage());
      exec(basedir, List.of("vite", "build"));
    }
    buildContext.refresh(output);
  }

  /**
   * In an incremental build: did a client module, a page entry or the frontend configuration
   * change? With Tailwind, Java sources count too, since it reads class names from them.
   */
  private boolean changed() {
    boolean tailwind = read(new File(basedir, "package.json")).contains("\"tailwindcss\"");
    for (String dir : new String[] {"src/main/java", "src/main/frontend"}) {
      File root = new File(basedir, dir);
      if (!root.isDirectory()) {
        continue;
      }
      Scanner scanner = buildContext.newScanner(root);
      scanner.setIncludes(tailwind ? new String[] {"**/*"} : FRONTEND_SOURCES);
      scanner.scan();
      if (scanner.getIncludedFiles().length > 0) {
        return true;
      }
    }
    for (String config : CONFIG) {
      if (buildContext.hasDelta(config)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A tool from node_modules, run by Node directly, not through npm or a shell: vite or tsc. Node
   * is the one frontend-maven-plugin installed in the project, else the one on the PATH.
   */
  void exec(File basedir, List<String> args) throws MojoExecutionException {
    String script = args.get(0).equals("tsc") ? "node_modules/typescript/bin/tsc" : "node_modules/vite/bin/vite.js";
    File tool = new File(basedir, script);
    if (!tool.isFile()) {
      throw new MojoExecutionException("j2act: " + tool + " is missing; install the npm packages first");
    }
    File nodeDir = new File(basedir, "node");
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    File node = new File(nodeDir, windows ? "node.exe" : "node");
    List<String> command = new ArrayList<>();
    command.add(node.isFile() ? node.getAbsolutePath() : "node");
    command.add(tool.getAbsolutePath());
    command.addAll(args.subList(1, args.size()));
    run(basedir, command, nodeDir);
  }

  static String read(File file) {
    try {
      return file.isFile() ? new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }

  void run(File basedir, List<String> command, File nodeDir) throws MojoExecutionException {
    getLog().info("j2act: " + String.join(" ", command));
    ProcessBuilder builder = new ProcessBuilder(command).directory(basedir).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    String pathKey = env.containsKey("Path") ? "Path" : "PATH";
    if (nodeDir.isDirectory()) {
      env.put(pathKey, nodeDir.getAbsolutePath() + File.pathSeparator + env.getOrDefault(pathKey, ""));
    }
    try {
      Process process = builder.start();
      java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
      try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line; (line = out.readLine()) != null; ) {
          getLog().info(line);
          tail.addLast(line.replaceAll("\\p{Cntrl}\\[[0-9;]*m", ""));
          if (tail.size() > 20) {
            tail.removeFirst();
          }
        }
      }
      int exit = process.waitFor();
      if (exit != 0) {
        // The tool's own last lines go into the error: m2e shows the message in Problems, not the log.
        throw new MojoExecutionException("j2act: " + String.join(" ", command) + " failed with exit code " + exit
          + ":\n" + String.join("\n", tail));
      }
    } catch (IOException e) {
      throw new MojoExecutionException("j2act: could not run " + command.get(0), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("j2act: interrupted", e);
    }
  }
}
