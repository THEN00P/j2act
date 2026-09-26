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
 * SPIKE: builds the client modules with the package's build script (tsc and Vite) into
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
    if (buildContext.isIncremental() && !changed()) {
      getLog().debug("j2act: no frontend file changed");
      return;
    }
    File nodeDir = new File(basedir, "node");
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    File node = new File(nodeDir, windows ? "node.exe" : "node");
    String nodeCommand = node.isFile() ? node.getAbsolutePath() : "node";
    boolean pnpm = new File(basedir, "pnpm-lock.yaml").isFile();
    File cli = new File(nodeDir, pnpm ? "node_modules/pnpm/bin/pnpm.cjs" : "node_modules/npm/bin/npm-cli.js");
    List<String> command = new ArrayList<>();
    if (cli.isFile()) {
      command.add(nodeCommand);
      command.add(cli.getAbsolutePath());
    } else {
      command.add(pnpm ? "pnpm" : "npm");
    }
    command.add("run");
    command.add("build");
    run(command, nodeDir);
    buildContext.refresh(new File(outputDirectory, "META-INF/j2act/vite"));
  }

  /** In an incremental build: did a client module, a page entry or the frontend configuration change? */
  private boolean changed() {
    for (String dir : new String[] {"src/main/java", "src/main/frontend"}) {
      File root = new File(basedir, dir);
      if (!root.isDirectory()) {
        continue;
      }
      Scanner scanner = buildContext.newScanner(root);
      scanner.setIncludes(FRONTEND_SOURCES);
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
    return buildContext.hasDelta(".j2act/types");
  }

  private void run(List<String> command, File nodeDir) throws MojoExecutionException {
    getLog().info("j2act: " + String.join(" ", command));
    ProcessBuilder builder = new ProcessBuilder(command).directory(basedir).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    String pathKey = env.containsKey("Path") ? "Path" : "PATH";
    if (nodeDir.isDirectory()) {
      env.put(pathKey, nodeDir.getAbsolutePath() + File.pathSeparator + env.getOrDefault(pathKey, ""));
    }
    try {
      Process process = builder.start();
      try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line; (line = out.readLine()) != null; ) {
          getLog().info(line);
        }
      }
      int exit = process.waitFor();
      if (exit != 0) {
        throw new MojoExecutionException("j2act: " + String.join(" ", command) + " failed with exit code " + exit);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("j2act: could not run " + command.get(0), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("j2act: interrupted", e);
    }
  }
}
