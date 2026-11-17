package dev.j2act.gradle;

import j2act.enhancer.Enhancer;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * j2actEnhance (ADR 0036): an enhanced copy of the main classes, which jar, war, bootJar and bootWar package in their
 * place. The class folders, which run, test, IDEs, explodedWar and dev mode use, stay plain.
 */
@CacheableTask
public abstract class EnhanceTask extends DefaultTask {

  /** The class folders of the main source set. */
  @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getClasses();

  /** What they were compiled against. */
  @CompileClasspath
  public abstract ConfigurableFileCollection getClasspath();

  @OutputDirectory
  public abstract DirectoryProperty getOutput();

  /** Copies the classes without enhancing them: the app runs the same, re-running whole views. */
  @Input
  public abstract Property<Boolean> getSkip();

  /** Packages whose classes stay plain store classes. */
  @Input
  public abstract org.gradle.api.provider.ListProperty<String> getTrackExcludes();

  @TaskAction
  public void enhance() throws IOException {
    Path out = getOutput().get().getAsFile().toPath();
    clear(out);
    for (File dir : getClasses().getFiles()) {
      if (dir.isDirectory()) {
        copy(dir.toPath(), out);
      }
    }
    if (getSkip().get()) {
      getLogger().lifecycle("j2act: not enhanced (j2act.enhance.skip)");
      return;
    }
    List<Path> classpath = new ArrayList<>();
    String core = null;
    for (File file : getClasspath().getFiles()) {
      classpath.add(file.toPath());
      String name = file.getName();
      if (name.startsWith("j2act-core-") && name.endsWith(".jar")) {
        core = name.substring("j2act-core-".length(), name.length() - ".jar".length());
      }
    }
    if (core == null) {
      getLogger().lifecycle("j2act: no j2act-core on the classpath, nothing to enhance");
      return;
    }
    if (!core.equals(Enhancer.VERSION)) {
      throw new GradleException("j2act-core is " + core + " but the j2act plugin is " + Enhancer.VERSION
        + ": classes enhanced by one call into the other (ADR 0036), so use the same version of both");
    }
    getLogger().lifecycle("j2act: " + new Enhancer(classpath).withTrackExcludes(getTrackExcludes().get()).enhance(out));
  }

  private static void clear(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        if (!p.equals(dir)) {
          Files.delete(p);
        }
      }
    }
  }

  private static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
        Path target = to.resolve(from.relativize(file).toString());
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
