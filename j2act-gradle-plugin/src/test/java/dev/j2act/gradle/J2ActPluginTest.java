package dev.j2act.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What id("dev.j2act") sets up (ADR 0025), checked without running npm: tasks, defaults that
 * the build script overrides, and the Eclipse files Buildship does not write itself.
 * j2act-processor comes from the local repository (./mvnw install).
 */
class J2ActPluginTest {

  @TempDir Path project;

  static Stream<String> gradleVersions() {
    return Stream.of(System.getProperty("gradle.versions", "9.8.0").split(","));
  }

  private void write(String path, String text) throws IOException {
    Path file = project.resolve(path);
    Files.createDirectories(file.getParent());
    Files.write(file, text.getBytes(StandardCharsets.UTF_8));
  }

  private String read(String path) throws IOException {
    return new String(Files.readAllBytes(project.resolve(path)), StandardCharsets.UTF_8);
  }

  private void setUp(String packageJson, String extraBuild) throws IOException {
    write("settings.gradle", "rootProject.name = 'demo'\n");
    write("build.gradle", "plugins {\n  id 'war'\n  id 'dev.j2act'\n}\n"
      + "repositories {\n  mavenLocal()\n  mavenCentral()\n}\n" + extraBuild);
    if (packageJson != null) {
      write("package.json", packageJson);
      write("tsconfig.json", "{}");
    }
    write("src/main/java/demo/Hello.java", "package demo;\n\npublic class Hello {\n}\n");
    write("src/main/java/demo/Hello.client.js", "export const hello = {};\n");
  }

  private BuildResult run(String gradle, String... tasks) {
    return GradleRunner.create()
      .withProjectDir(project.toFile())
      .withPluginClasspath()
      .withGradleVersion(gradle)
      .withArguments(tasks)
      .build();
  }

  private static final String SHOW = "tasks.register('show') {\n"
    + "  def bundle = tasks.named('j2actBundle')\n"
    + "  def nodeVersion = node.version\n"
    + "  def download = node.download\n"
    + "  doLast {\n"
    + "    println 'bundle=' + bundle.get().class.name\n"
    + "    println 'node=' + nodeVersion.get() + ' download=' + download.get()\n"
    + "  }\n"
    + "}\n";

  @ParameterizedTest @MethodSource("gradleVersions")
  void aPackageJsonBringsTheViteTasksWithDefaultsTheBuildScriptOverrides(String gradle) throws IOException {
    setUp("{\"name\":\"demo\",\"packageManager\":\"pnpm@10.32.1\"}",
      SHOW + "node {\n  version = '22.12.0'\n  download = false\n}\n");
    String output = run(gradle, "tasks", "--all", "show").getOutput();
    for (String task : new String[] {"j2actBundle", "j2actTypecheck", "explodedWar", "j2actEclipseApt"}) {
      assertTrue(output.contains(task), task + " missing: " + output);
    }
    assertTrue(output.contains("bundle=com.github.gradle.node.pnpm.task.PnpmTask"), "packageManager picks pnpm: " + output);
    assertTrue(output.contains("node=22.12.0 download=false"), "node { } wins over the plugin's conventions: " + output);
  }

  @ParameterizedTest @MethodSource("gradleVersions")
  void withoutAPackageJsonThereIsNoViteAndNoNode(String gradle) throws IOException {
    setUp(null, "");
    String output = run(gradle, "tasks", "--all").getOutput();
    assertFalse(output.contains("j2actBundle"), output);
    assertFalse(output.contains("nodeSetup"), output);
    assertTrue(output.contains("explodedWar"), output);
  }

  @ParameterizedTest @MethodSource("gradleVersions")
  void plainClientModulesBesideTheJavaSourcesAreResources(String gradle) throws IOException {
    setUp(null, "");
    run(gradle, "processResources");
    assertTrue(Files.isRegularFile(project.resolve("build/resources/main/demo/Hello.client.js")));
  }

  /**
   * Every task the plugin adds or configures serializes into the configuration cache, and the
   * entry is reused. A dry run, so no npm: the cache holds the task graph either way.
   */
  @ParameterizedTest @MethodSource("gradleVersions")
  void theConfigurationCacheStoresAndReusesTheBuild(String gradle) throws IOException {
    setUp("{\"name\":\"demo\"}", "");
    String[] tasks = {"--configuration-cache", "--dry-run", "build", "explodedWar", "j2actEclipseApt"};
    String first = run(gradle, tasks).getOutput();
    assertTrue(first.contains("Configuration cache entry stored"), first);
    assertTrue(first.contains(":j2actBundle SKIPPED") && first.contains(":j2actTypecheck SKIPPED"), first);
    String second = run(gradle, tasks).getOutput();
    assertTrue(second.contains("Configuration cache entry reused"), second);
  }

  @ParameterizedTest @MethodSource("gradleVersions")
  void eclipseGetsAnnotationProcessingAndAWtpDeploymentThatCarriesItsOutputs(String gradle) throws IOException {
    setUp("{\"name\":\"demo\"}", "");
    run(gradle, "j2actEclipseApt", "eclipseWtp");
    assertTrue(read(".factorypath").contains("j2act-processor"), read(".factorypath"));
    String apt = read(".settings/org.eclipse.jdt.apt.core.prefs");
    assertTrue(apt.contains("org.eclipse.jdt.apt.aptEnabled=true"), apt);
    assertTrue(apt.contains("org.eclipse.jdt.apt.genSrcDir=bin/generated-sources/annotations"), "project-relative: " + apt);
    String jdt = read(".settings/org.eclipse.jdt.core.prefs");
    assertTrue(jdt.contains("org.eclipse.jdt.core.compiler.processAnnotations=enabled"), jdt);
    assertTrue(jdt.contains("*.ts"), "TypeScript is not copied as a resource: " + jdt);
    String component = read(".settings/org.eclipse.wst.common.component");
    for (String folder : new String[] {"bin/main", "bin/default", "build/j2act"}) {
      assertTrue(component.contains("source-path=\"" + folder + "\""), folder + " not published: " + component);
    }
    assertTrue(read(".settings/org.eclipse.wst.common.project.facet.core.xml").contains("facet=\"jst.web\" version=\"6.0\""));
  }
}
