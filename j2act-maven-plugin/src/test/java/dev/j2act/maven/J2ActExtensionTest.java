package dev.j2act.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The defaults {@code <extensions>true</extensions>} applies, and that the pom's own settings win (ADR 0025). */
class J2ActExtensionTest {

  @TempDir Path dir;

  private MavenProject project(String packaging, String packageJson) throws IOException {
    Model model = new Model();
    model.setGroupId("com.example");
    model.setArtifactId("app");
    model.setVersion("1");
    model.setPackaging(packaging);
    Build build = new Build();
    Resource resources = new Resource();
    resources.setDirectory(dir.resolve("src/main/resources").toString());
    build.addResource(resources);
    Plugin compiler = plugin(J2ActExtension.COMPILER);
    PluginExecution compile = new PluginExecution();
    compile.setId("default-compile");
    compile.addGoal("compile");
    compiler.addExecution(compile);
    build.addPlugin(compiler);
    build.addPlugin(plugin(J2ActExtension.PLUGIN));
    model.setBuild(build);
    MavenProject project = new MavenProject(model);
    project.setFile(dir.resolve("pom.xml").toFile());
    if (packageJson != null) {
      Files.write(dir.resolve("package.json"), packageJson.getBytes(StandardCharsets.UTF_8));
    }
    return project;
  }

  private static Plugin plugin(String key) {
    Plugin plugin = new Plugin();
    plugin.setGroupId(key.substring(0, key.indexOf(':')));
    plugin.setArtifactId(key.substring(key.indexOf(':') + 1));
    plugin.setVersion("0.1.0");
    return plugin;
  }

  private static void apply(MavenProject project) {
    apply(project, new Properties());
  }

  private static void apply(MavenProject project, Properties userProperties) {
    J2ActExtension.apply(project, project.getBuild().getPluginsAsMap().get(J2ActExtension.PLUGIN), userProperties);
  }

  private static List<String> processorPath(Object configuration) {
    List<String> path = new ArrayList<>();
    for (Xpp3Dom each : ((Xpp3Dom) configuration).getChild("annotationProcessorPaths").getChildren()) {
      path.add(each.getChild("artifactId").getValue() + ":" + (each.getChild("version") == null ? "" : each.getChild("version").getValue()));
    }
    return path;
  }

  private static String config(Plugin plugin, String name) {
    Xpp3Dom child = ((Xpp3Dom) plugin.getConfiguration()).getChild(name);
    return child == null ? null : child.getValue();
  }

  @Test void aBareWarGetsEverything() throws IOException {
    MavenProject project = project("war", "{\"devDependencies\":{\"vite\":\"8\"}}");
    apply(project);
    Build build = project.getBuild();

    PluginExecution own = build.getPluginsAsMap().get(J2ActExtension.PLUGIN).getExecutions().get(0);
    assertEquals(List.of("bundle", "typecheck"), own.getGoals());

    Plugin compiler = build.getPluginsAsMap().get(J2ActExtension.COMPILER);
    assertEquals(List.of("j2act-processor:0.1.0"), processorPath(compiler.getConfiguration()));
    assertEquals(List.of("j2act-processor:0.1.0"), processorPath(compiler.getExecutions().get(0).getConfiguration()));

    assertEquals(2, build.getResources().size(), "src/main/resources stays");
    Resource java = build.getResources().get(1);
    assertEquals(new File(dir.toFile(), "src/main/java").getAbsolutePath(), java.getDirectory());
    assertEquals(List.of(J2ActExtension.CLIENT_RESOURCES), java.getIncludes());

    assertEquals("jdt_apt", project.getProperties().getProperty("m2e.apt.activation"));

    Plugin node = build.getPluginsAsMap().get(J2ActExtension.FRONTEND);
    assertEquals(J2ActExtension.NODE_VERSION, config(node, "nodeVersion"));
    assertEquals("install-node-and-npm", node.getExecutions().get(0).getGoals().get(0));
    assertEquals("npm", node.getExecutions().get(1).getGoals().get(0));
    assertEquals(J2ActExtension.NODE_VERSION,
      ((Xpp3Dom) node.getExecutions().get(0).getConfiguration()).getChild("nodeVersion").getValue());

    assertEquals(J2ActExtension.EXPLODED_VERSION, build.getPluginsAsMap().get(J2ActExtension.EXPLODED).getVersion());
  }

  @Test void withoutPackageJsonOrWarThereIsNoNodeAndNoExplodedGoal() throws IOException {
    MavenProject project = project("jar", null);
    apply(project);
    assertNull(project.getBuild().getPluginsAsMap().get(J2ActExtension.FRONTEND));
    assertNull(project.getBuild().getPluginsAsMap().get(J2ActExtension.EXPLODED));
    assertEquals("jdt_apt", project.getProperties().getProperty("m2e.apt.activation"));
  }

  @Test void thePomsOwnSettingsWin() throws IOException {
    MavenProject project = project("war", "{}");
    Build build = project.getBuild();
    PluginExecution bundleOnly = new PluginExecution();
    bundleOnly.setId("mine");
    bundleOnly.addGoal("bundle");
    build.getPluginsAsMap().get(J2ActExtension.PLUGIN).addExecution(bundleOnly);
    Xpp3Dom configuration = new Xpp3Dom("configuration");
    Xpp3Dom paths = new Xpp3Dom("annotationProcessorPaths");
    Xpp3Dom lombok = new Xpp3Dom("path");
    for (String[] child : new String[][] {{"groupId", "org.projectlombok"}, {"artifactId", "lombok"}, {"version", "1.18.38"}}) {
      Xpp3Dom element = new Xpp3Dom(child[0]);
      element.setValue(child[1]);
      lombok.addChild(element);
    }
    paths.addChild(lombok);
    configuration.addChild(paths);
    build.getPluginsAsMap().get(J2ActExtension.COMPILER).setConfiguration(configuration);
    Resource java = new Resource();
    java.setDirectory(dir.resolve("src/main/java").toString());
    java.addInclude("**/*.xml");
    build.addResource(java);
    project.getProperties().setProperty("m2e.apt.activation", "maven_execution");
    build.addPlugin(plugin(J2ActExtension.EXPLODED));
    build.flushPluginMap();

    apply(project);
    apply(project);

    assertEquals(List.of("bundle"), build.getPluginsAsMap().get(J2ActExtension.PLUGIN).getExecutions().get(0).getGoals());
    assertEquals(1, build.getPluginsAsMap().get(J2ActExtension.PLUGIN).getExecutions().size());
    assertEquals(List.of("lombok:1.18.38", "j2act-processor:0.1.0"),
      processorPath(build.getPluginsAsMap().get(J2ActExtension.COMPILER).getConfiguration()), "added once, after the pom's");
    assertEquals(2, build.getResources().size(), "the pom's src/main/java resource, no second one");
    assertEquals(List.of("**/*.xml"), build.getResources().get(1).getIncludes());
    assertEquals("maven_execution", project.getProperties().getProperty("m2e.apt.activation"));
    assertEquals("0.1.0", build.getPluginsAsMap().get(J2ActExtension.EXPLODED).getVersion());
  }

  @Test void aDeclaredFrontendPluginIsLeftAlone() throws IOException {
    MavenProject project = project("jar", "{}");
    Plugin mine = plugin(J2ActExtension.FRONTEND);
    project.getBuild().addPlugin(mine);
    apply(project);
    assertEquals(0, project.getBuild().getPluginsAsMap().get(J2ActExtension.FRONTEND).getExecutions().size());
  }

  @Test void thePackageManagerComesFromPackageJsonThenTheLockfile() throws IOException {
    MavenProject pnpm = project("jar", "{\"packageManager\": \"pnpm@9.15.0+sha512.abc\"}");
    apply(pnpm);
    Plugin node = pnpm.getBuild().getPluginsAsMap().get(J2ActExtension.FRONTEND);
    assertEquals("install-node-and-pnpm", node.getExecutions().get(0).getGoals().get(0));
    assertEquals("pnpm", node.getExecutions().get(1).getGoals().get(0));
    assertEquals("9.15.0", config(node, "pnpmVersion"));

    Files.write(dir.resolve("yarn.lock"), new byte[0]);
    MavenProject yarn = project("jar", "{}");
    apply(yarn);
    node = yarn.getBuild().getPluginsAsMap().get(J2ActExtension.FRONTEND);
    assertEquals("install-node-and-yarn", node.getExecutions().get(0).getGoals().get(0));
    assertEquals(J2ActExtension.YARN_VERSION, config(node, "yarnVersion"));
  }

  @Test void frontendPluginPropertiesStayItsOwn() throws IOException {
    MavenProject project = project("jar", "{\"packageManager\":\"pnpm@10.0.0\"}");
    project.getProperties().setProperty("nodeVersion", "v22.1.0");
    Properties user = new Properties();
    user.setProperty("pnpmVersion", "10.1.0");
    apply(project, user);
    Plugin node = project.getBuild().getPluginsAsMap().get(J2ActExtension.FRONTEND);
    assertNull(config(node, "nodeVersion"), "frontend-maven-plugin reads ${nodeVersion} itself");
    assertNull(config(node, "pnpmVersion"));
    assertFalse(node.getExecutions().isEmpty());
  }
}
