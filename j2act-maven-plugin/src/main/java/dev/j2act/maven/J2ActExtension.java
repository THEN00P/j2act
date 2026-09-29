package dev.j2act.maven;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * {@code <extensions>true</extensions>} on j2act-maven-plugin (ADR 0025): the pom boilerplate
 * as defaults, as the Kotlin Maven plugin's extension does it, and as id("dev.j2act") does it for
 * Gradle. Whatever the pom declares wins. Maven and m2e (Eclipse, VS Code) both run it after
 * reading the projects, so the IDEs see the same build as mvn does.
 * <ul>
 * <li>the bundle and typecheck goals, when the pom lists no executions of its own;</li>
 * <li>j2act-processor on the compiler's processor path, beside any the pom lists;</li>
 * <li>src/main/java as a resource folder for plain client modules and what they import;</li>
 * <li>m2e.apt.activation=jdt_apt, since m2e runs no annotation processors by default;</li>
 * <li>with a package.json, Node and the package install through frontend-maven-plugin, unless
 * the pom declares that plugin itself;</li>
 * <li>for a WAR, exploded-hotswap's mvn exploded-hotswap:exploded.</li>
 * </ul>
 */
@Named("j2act")
@Singleton
public class J2ActExtension extends AbstractMavenLifecycleParticipant {

  static final String PLUGIN = "dev.j2act:j2act-maven-plugin";
  static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
  static final String FRONTEND = "com.github.eirslett:frontend-maven-plugin";
  static final String FRONTEND_VERSION = "2.0.2";
  static final String EXPLODED = "io.github.then00p:exploded-hotswap-maven-plugin";
  static final String EXPLODED_VERSION = "0.1.0-SNAPSHOT";
  static final String NODE_VERSION = "v24.14.0";
  static final String PNPM_VERSION = "10.32.1";
  static final String YARN_VERSION = "v1.22.22";
  static final String[] CLIENT_RESOURCES = {"**/*.js", "**/*.mjs", "**/*.cjs", "**/*.css", "**/*.json"};
  private static final Pattern PACKAGE_MANAGER =
    Pattern.compile("\"packageManager\"\\s*:\\s*\"(npm|pnpm|yarn)@([0-9][^\"+]*)");

  @Override public void afterProjectsRead(MavenSession session) {
    for (MavenProject project : session.getProjects()) {
      Plugin self = project.getBuild().getPluginsAsMap().get(PLUGIN);
      // The extension sees the whole reactor; only the projects that use the plugin change.
      if (self != null) {
        apply(project, self, session.getUserProperties());
      }
    }
  }

  static void apply(MavenProject project, Plugin self, Properties userProperties) {
    Build build = project.getBuild();
    if (self.getExecutions().isEmpty()) {
      PluginExecution execution = new PluginExecution();
      execution.setId("default");
      execution.addGoal("bundle");
      execution.addGoal("typecheck");
      self.addExecution(execution);
    }
    processorPath(build.getPluginsAsMap().get(COMPILER), self.getVersion());
    resources(project);
    if (project.getProperties().getProperty("m2e.apt.activation") == null) {
      project.getProperties().setProperty("m2e.apt.activation", "jdt_apt");
    }
    File packageJson = new File(project.getBasedir(), "package.json");
    if (packageJson.isFile() && !build.getPluginsAsMap().containsKey(FRONTEND)) {
      build.addPlugin(node(project, userProperties, BundleMojo.read(packageJson)));
    }
    if ("war".equals(project.getPackaging()) && !build.getPluginsAsMap().containsKey(EXPLODED)) {
      build.addPlugin(plugin(EXPLODED, EXPLODED_VERSION));
    }
    build.flushPluginMap();
  }

  /**
   * j2act-processor joins the processor path, at the plugin's own version. Maven copies the
   * plugin's configuration into each execution while it reads the pom, so both get it.
   */
  static void processorPath(Plugin compiler, String version) {
    if (compiler == null) {
      return;
    }
    compiler.setConfiguration(withProcessor((Xpp3Dom) compiler.getConfiguration(), version));
    for (PluginExecution execution : compiler.getExecutions()) {
      execution.setConfiguration(withProcessor((Xpp3Dom) execution.getConfiguration(), version));
    }
  }

  private static Xpp3Dom withProcessor(Xpp3Dom configuration, String version) {
    if (configuration == null) {
      configuration = new Xpp3Dom("configuration");
    }
    Xpp3Dom paths = configuration.getChild("annotationProcessorPaths");
    if (paths == null) {
      paths = new Xpp3Dom("annotationProcessorPaths");
      configuration.addChild(paths);
    }
    for (Xpp3Dom path : paths.getChildren()) {
      Xpp3Dom artifactId = path.getChild("artifactId");
      if (artifactId != null && "j2act-processor".equals(artifactId.getValue())) {
        return configuration;
      }
    }
    Xpp3Dom path = new Xpp3Dom("path");
    path.addChild(element("groupId", "dev.j2act"));
    path.addChild(element("artifactId", "j2act-processor"));
    path.addChild(element("version", version));
    paths.addChild(path);
    return configuration;
  }

  /** src/main/java as one more resource folder, for the .js and .css beside the Java sources. */
  static void resources(MavenProject project) {
    File java = new File(project.getBasedir(), "src/main/java");
    for (Resource resource : project.getBuild().getResources()) {
      if (resource.getDirectory() != null && new File(resource.getDirectory()).getAbsoluteFile().equals(java.getAbsoluteFile())) {
        return;
      }
    }
    Resource resource = new Resource();
    resource.setDirectory(java.getAbsolutePath());
    resource.setIncludes(List.of(CLIENT_RESOURCES));
    project.getBuild().addResource(resource);
  }

  /**
   * Node and the package manager of package.json's packageManager field (as Corepack reads it),
   * else of the lockfile, else npm, installed and run as in any Maven build with a frontend.
   * nodeVersion, pnpmVersion and yarnVersion stay frontend-maven-plugin's own properties.
   */
  static Plugin node(MavenProject project, Properties userProperties, String packageJson) {
    String manager = "npm";
    String version = null;
    Matcher declared = PACKAGE_MANAGER.matcher(packageJson);
    if (declared.find()) {
      manager = declared.group(1);
      version = declared.group(2);
    } else if (new File(project.getBasedir(), "pnpm-lock.yaml").isFile()) {
      manager = "pnpm";
    } else if (new File(project.getBasedir(), "yarn.lock").isFile()) {
      manager = "yarn";
    }
    Plugin plugin = plugin(FRONTEND, FRONTEND_VERSION);
    Xpp3Dom configuration = new Xpp3Dom("configuration");
    if (!defined(project, userProperties, "nodeVersion")) {
      configuration.addChild(element("nodeVersion", NODE_VERSION));
    }
    if (manager.equals("pnpm") && !defined(project, userProperties, "pnpmVersion")) {
      configuration.addChild(element("pnpmVersion", version != null ? version : PNPM_VERSION));
    }
    if (manager.equals("yarn") && !defined(project, userProperties, "yarnVersion")) {
      configuration.addChild(element("yarnVersion", version != null ? "v" + version : YARN_VERSION));
    }
    plugin.setConfiguration(configuration);
    plugin.addExecution(execution("j2act-install-node", "install-node-and-" + manager));
    // npm install, frontend-maven-plugin's default, not npm ci: Eclipse and VS Code run it on
    // every full build, and npm ci deletes node_modules under the running dev watcher.
    plugin.addExecution(execution("j2act-install", manager));
    // Maven has already copied plugin configurations into executions by now, so these get their own.
    for (PluginExecution execution : plugin.getExecutions()) {
      execution.setConfiguration(new Xpp3Dom(configuration));
    }
    return plugin;
  }

  private static boolean defined(MavenProject project, Properties userProperties, String name) {
    return project.getProperties().getProperty(name) != null || userProperties.getProperty(name) != null;
  }

  private static Plugin plugin(String key, String version) {
    Plugin plugin = new Plugin();
    plugin.setGroupId(key.substring(0, key.indexOf(':')));
    plugin.setArtifactId(key.substring(key.indexOf(':') + 1));
    plugin.setVersion(version);
    return plugin;
  }

  private static PluginExecution execution(String id, String goal) {
    PluginExecution execution = new PluginExecution();
    execution.setId(id);
    execution.addGoal(goal);
    return execution;
  }

  private static Xpp3Dom element(String name, String value) {
    Xpp3Dom element = new Xpp3Dom(name);
    element.setValue(value);
    return element;
  }
}
