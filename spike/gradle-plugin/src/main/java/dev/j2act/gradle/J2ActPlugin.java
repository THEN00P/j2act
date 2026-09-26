package dev.j2act.gradle;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;

import com.github.gradle.node.NodeExtension;
import com.github.gradle.node.NodePlugin;
import com.github.gradle.node.npm.task.NpmTask;
import com.github.gradle.node.pnpm.task.PnpmTask;

/**
 * SPIKE: everything a j2act app needs from Gradle. The annotation processor, client modules
 * beside the Java sources as resources, and, with a package.json, Node plus a Vite build whose
 * output joins the main source set's classpath.
 */
public class J2ActPlugin implements Plugin<Project> {

  static final String VERSION = "0.1.0-SNAPSHOT";
  /** What a client module's build reads. */
  static final List<String> FRONTEND_SOURCES =
    List.of("**/*.ts", "**/*.tsx", "**/*.mts", "**/*.js", "**/*.jsx", "**/*.mjs", "**/*.cjs", "**/*.css");

  @Override public void apply(Project project) {
    project.getPluginManager().apply(JavaPlugin.class);
    project.getDependencies().add("annotationProcessor", "dev.j2act:j2act-processor:" + VERSION);

    // Plain JavaScript client modules need no build: they and what they import are resources.
    project.getTasks().named("processResources", ProcessResources.class, task ->
      task.from("src/main/java", spec -> spec.include("**/*.js", "**/*.mjs", "**/*.cjs", "**/*.css", "**/*.json")));

    // The dev token names this project folder; archives never carry it. On the root spec: War
    // and BootJar add WEB-INF/classes and BOOT-INF/classes beside the main spec, out of its reach.
    project.getTasks().withType(Jar.class).configureEach(jar ->
      jar.getRootSpec().exclude("**/META-INF/j2act/dev.json"));

    if (project.file("package.json").exists()) {
      vite(project);
    }
  }

  private void vite(Project project) {
    project.getPluginManager().apply(NodePlugin.class);
    NodeExtension node = project.getExtensions().getByType(NodeExtension.class);
    node.getDownload().convention(true);
    node.getVersion().convention("24.14.0");
    node.getNpmInstallCommand().convention(project.file("package-lock.json").exists() ? "ci" : "install");

    boolean pnpm = project.file("pnpm-lock.yaml").exists();
    String install = pnpm ? "pnpmInstall" : "npmInstall";
    File out = project.getLayout().getBuildDirectory().dir("j2act").get().getAsFile();

    // The package's own build script: in the usual Vite project, tsc then vite build.
    TaskProvider<? extends Task> bundle = pnpm
      ? project.getTasks().register("j2actBundle", PnpmTask.class, t -> t.getPnpmCommand().set(List.of("run", "build")))
      : project.getTasks().register("j2actBundle", NpmTask.class, t -> t.getNpmCommand().set(List.of("run", "build")));
    bundle.configure(task -> {
      task.setGroup("build");
      task.setDescription("Builds the client modules with the package's build script (tsc and Vite).");
      // tsc checks against the .types.d.ts the annotation processor writes during compileJava.
      task.dependsOn(install, "compileJava");
      task.getInputs().files(project.fileTree("src/main/java", tree -> tree.include(FRONTEND_SOURCES)))
        .withPropertyName("clientSources");
      task.getInputs().files(project.fileTree("src/main/frontend")).withPropertyName("pageSources");
      task.getInputs().files(project.fileTree(project.getProjectDir(), tree -> tree.include(
        "package.json", "package-lock.json", "pnpm-lock.yaml", "vite.config.*", "tsconfig*.json")))
        .withPropertyName("config");
      task.getInputs().files(project.fileTree(".j2act/types", tree -> tree.include("**/*.d.ts")))
        .withPropertyName("clientTypes");
      task.getOutputs().dir(out).withPropertyName("classpathDir");
    });

    // The output is one more classpath folder of the main source set: run, test, war and jar see it.
    SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
      .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    main.getOutput().dir(Map.of("builtBy", bundle), out);

    // Eclipse (Buildship) builds with Vite on every auto build, and runs with the output on its classpath.
    project.getPluginManager().apply(EclipsePlugin.class);
    EclipseModel eclipse = project.getExtensions().getByType(EclipseModel.class);
    eclipse.autoBuildTasks(bundle);
    // A fresh clone imported into Eclipse or VS Code gets Node and node_modules on import, so the
    // first Run can start the app's Vite watcher.
    eclipse.synchronizationTasks(install);
    eclipse.getClasspath().getFile().whenMerged(cp -> {
      org.gradle.plugins.ide.eclipse.model.Classpath classpath = (org.gradle.plugins.ide.eclipse.model.Classpath) cp;
      classpath.getEntries().add(new Library(classpath.fileReference(out)));
    });
  }
}
