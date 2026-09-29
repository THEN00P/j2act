package dev.j2act.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.WarPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.EclipseWtpPlugin;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Facet;
import org.gradle.plugins.ide.eclipse.model.WtpFacet;

import com.github.gradle.node.NodeExtension;
import com.github.gradle.node.NodePlugin;
import com.github.gradle.node.npm.task.NpmTask;
import com.github.gradle.node.pnpm.task.PnpmTask;
import com.github.gradle.node.yarn.task.YarnTask;

/**
 * id("dev.j2act") (ADR 0025): everything a j2act app needs from Gradle, as defaults the build script can override.
 * The annotation processor, client modules beside the Java sources as resources, and, with a
 * package.json, Node (node-gradle, configured only by convention), a Vite build whose output
 * joins the main source set, a tsc check, and the Eclipse setup Buildship leaves out.
 */
public class J2ActPlugin implements Plugin<Project> {

  static final String VERSION = "0.1.0-SNAPSHOT";
  private static final Pattern PACKAGE_MANAGER = Pattern.compile("\"packageManager\"\\s*:\\s*\"(npm|pnpm|yarn)@");

  @Override public void apply(Project project) {
    project.getPluginManager().apply(JavaPlugin.class);
    project.getDependencies().add("annotationProcessor", "dev.j2act:j2act-processor:" + VERSION);

    // Plain JavaScript client modules need no build: they and what they import are resources.
    project.getTasks().named("processResources", ProcessResources.class, task ->
      task.from("src/main/java", spec -> spec.include("**/*.js", "**/*.mjs", "**/*.cjs", "**/*.css", "**/*.json")));

    eclipse(project);
    // explodedWar: the WAR as a folder with the editor's classes, for IDE servers, dev mode and HotSwap.
    project.getPluginManager().apply("io.github.then00p.exploded-hotswap");
    if (project.file("package.json").exists()) {
      vite(project);
    }
  }

  private void vite(Project project) {
    project.getPluginManager().apply(NodePlugin.class);
    NodeExtension node = project.getExtensions().getByType(NodeExtension.class);
    // Conventions only: whatever the build script sets in node { } wins.
    node.getDownload().convention(true);
    node.getVersion().convention("24.14.0");
    // npm install, node-gradle's default, not npm ci: a Gradle refresh in the IDE runs the install,
    // and npm ci deletes node_modules under the running dev watcher, which Windows stops halfway.

    String manager = packageManager(project);
    String install = manager.equals("pnpm") ? "pnpmInstall" : manager.equals("yarn") ? "yarn" : "npmInstall";
    File out = project.getLayout().getBuildDirectory().dir("j2act").get().getAsFile();
    boolean tailwind = mentions(project, "tailwindcss");

    // vite build, as `npm exec vite build` and its pnpm and yarn forms.
    TaskProvider<? extends Task> bundle = packageTask(project, manager, "j2actBundle", List.of("vite", "build"));
    bundle.configure(task -> {
      task.setGroup("build");
      task.setDescription("Builds the client modules and page entries with Vite onto the classpath.");
      task.dependsOn(install);
      // Tailwind reads class names from the Java sources, so they are inputs when it is in use.
      task.getInputs().files(project.fileTree("src/main", tree -> {
        if (!tailwind) {
          tree.exclude("**/*.java");
        }
      })).withPropertyName("sources");
      task.getInputs().files(project.fileTree(project.getProjectDir(), tree -> tree.include(
        "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "vite.config.*")))
        .withPropertyName("config");
      task.getOutputs().dir(out).withPropertyName("classpathDir");
    });

    // Type errors fail `check`, and so `build`, but never compiling or running the app.
    if (project.file("tsconfig.json").exists()) {
      TaskProvider<? extends Task> typecheck = packageTask(project, manager, "j2actTypecheck",
        List.of("tsc", "-p", "tsconfig.json", "--noEmit"));
      typecheck.configure(task -> {
        task.setGroup("verification");
        task.setDescription("Type-checks the TypeScript client modules against the generated client types.");
        task.dependsOn(install, "compileJava");
        task.getInputs().files(project.fileTree("src/main", tree -> tree.include("**/*.ts", "**/*.tsx", "**/*.mts")))
          .withPropertyName("sources");
        task.getInputs().files(project.fileTree(".j2act/types")).withPropertyName("clientTypes");
        task.getInputs().files(project.fileTree(project.getProjectDir(), tree -> tree.include("tsconfig*.json", "package.json")))
          .withPropertyName("config");
        task.getOutputs().upToDateWhen(t -> true);
      });
      project.getTasks().named("check", check -> check.dependsOn(typecheck));
    }

    // The output is one more classpath folder of the main source set: run, test, war and jar see it.
    SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
      .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    main.getOutput().dir(Map.of("builtBy", bundle), out);

    EclipseModel eclipse = project.getExtensions().getByType(EclipseModel.class);
    // Buildship: Vite on every auto build, Node and node_modules on import.
    eclipse.autoBuildTasks(bundle);
    eclipse.synchronizationTasks(install);
    project.getPlugins().withType(EclipseWtpPlugin.class, wtp ->
      // WTP publishes and exports the Vite output with the classes.
      publish(eclipse, project.relativePath(out).replace('\\', '/')));
  }

  /**
   * Eclipse with Buildship runs no annotation processors: j2actEclipseApt writes the settings on
   * every Gradle refresh. A WAR project gets WTP, with a Jakarta EE 10 web facet in place of
   * Gradle's Servlet 2.4 default.
   */
  private void eclipse(Project project) {
    project.getPluginManager().apply(EclipsePlugin.class);
    EclipseModel eclipse = project.getExtensions().getByType(EclipseModel.class);
    TaskProvider<EclipseAptTask> apt = project.getTasks().register("j2actEclipseApt", EclipseAptTask.class, task -> {
      task.setGroup("ide");
      task.setDescription("Turns on annotation processing for Eclipse (Buildship does not).");
      task.getProcessorPath().from(project.getConfigurations().getByName("annotationProcessor"));
      task.getProjectDir().set(project.getLayout().getProjectDirectory());
    });
    eclipse.synchronizationTasks(apt);

    project.getPlugins().withType(WarPlugin.class, war -> {
      project.getPluginManager().apply(EclipseWtpPlugin.class);
      // WTP publishes what the source folders compile to, file by file, so files the annotation
      // processor wrote (.mount-params, the dev token) stayed behind; publish the folder whole,
      // as m2e-wtp does with target/classes. Added when merged: Gradle's resource() drops folders
      // that do not exist yet, and on a fresh import neither this one nor build/j2act does.
      publish(eclipse, "bin/main");
      // Eclipse writes the annotation processor's resources (.mount-params, the dev token) to the
      // default output folder. Making that bin/main too clashed: Buildship in VS Code renamed the
      // classes' folder to bin/main_.
      publish(eclipse, "bin/default");
      eclipse.getWtp().getFacet().getFile().whenMerged(f -> {
        WtpFacet facet = (WtpFacet) f;
        for (Facet each : facet.getFacets()) {
          if ("jst.web".equals(each.getName()) && "2.4".equals(each.getVersion())) {
            each.setVersion("6.0");
          }
        }
      });
    });
  }

  private static void publish(EclipseModel eclipse, String folder) {
    eclipse.getWtp().getComponent().getFile().whenMerged(c -> {
      org.gradle.plugins.ide.eclipse.model.WtpComponent component = (org.gradle.plugins.ide.eclipse.model.WtpComponent) c;
      component.getWbModuleEntries().add(new org.gradle.plugins.ide.eclipse.model.WbResource("/WEB-INF/classes", folder));
    });
  }

  private static TaskProvider<? extends Task> packageTask(Project project, String manager, String name, List<String> command) {
    if (manager.equals("pnpm")) {
      return project.getTasks().register(name, PnpmTask.class, t -> t.getPnpmCommand().set(concat("exec", command)));
    }
    if (manager.equals("yarn")) {
      return project.getTasks().register(name, YarnTask.class, t -> t.getYarnCommand().set(command));
    }
    return project.getTasks().register(name, NpmTask.class, t -> t.getNpmCommand().set(concat(List.of("exec", "--"), command)));
  }

  private static List<String> concat(Object head, List<String> tail) {
    List<String> all = new java.util.ArrayList<>();
    if (head instanceof List) {
      for (Object o : (List<?>) head) {
        all.add((String) o);
      }
    } else {
      all.add((String) head);
    }
    all.addAll(tail);
    return all;
  }

  /** package.json's packageManager field (as Corepack reads it), else the lockfile, else npm. */
  static String packageManager(Project project) {
    Matcher declared = PACKAGE_MANAGER.matcher(read(project.file("package.json")));
    if (declared.find()) {
      return declared.group(1);
    }
    if (project.file("pnpm-lock.yaml").exists()) {
      return "pnpm";
    }
    return project.file("yarn.lock").exists() ? "yarn" : "npm";
  }

  private static boolean mentions(Project project, String dependency) {
    return read(project.file("package.json")).contains("\"" + dependency + "\"");
  }

  private static String read(File file) {
    try {
      return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }
}
