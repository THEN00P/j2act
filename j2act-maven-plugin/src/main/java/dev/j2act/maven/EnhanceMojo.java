package dev.j2act.maven;

import j2act.enhancer.Enhancer;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Enhances what gets packaged (ADR 0036): a copy of the compiled classes, never the class folder itself, so an IDE or
 * dev mode running from target/classes runs plain classes and its hot code replace keeps working. The copy becomes the
 * build's output directory for what runs after it in the build: Maven reads ${project.build.outputDirectory} for a
 * goal's parameters only when that goal runs, so the jar and war plugins package the copy, while tests, in the phase
 * before, ran on the plain classes. A pom that sets a packaging plugin's classesDirectory gets what it set.
 */
@Mojo(name = "enhance", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true,
  requiresDependencyResolution = ResolutionScope.COMPILE)
public class EnhanceMojo extends AbstractMojo {

  static final String OUTPUT = "j2act-classes";

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  /** Where the enhanced copy goes, which the jar or war plugin packages. */
  @Parameter(defaultValue = "${project.build.directory}/" + OUTPUT)
  private File output;

  /** Copies the classes without enhancing them: the app runs the same, re-running whole views. */
  @Parameter(property = "j2act.enhance.skip", defaultValue = "false")
  private boolean skip;

  @Override public void execute() throws MojoExecutionException {
    Path classes = new File(project.getBuild().getOutputDirectory()).toPath();
    if (!Files.isDirectory(classes)) {
      return;
    }
    if (skip) {
      getLog().info("j2act: not enhanced (j2act.enhance.skip)");
      return;
    }
    String core = null;
    for (Artifact artifact : project.getArtifacts()) {
      if ("io.github.then00p.j2act".equals(artifact.getGroupId()) && "j2act-core".equals(artifact.getArtifactId())) {
        core = artifact.getBaseVersion();
      }
    }
    if (core == null) {
      getLog().info("j2act: no j2act-core on the classpath, nothing to enhance");
      return;
    }
    if (!core.equals(Enhancer.VERSION)) {
      throw new MojoExecutionException("j2act-core is " + core + " but j2act-maven-plugin is " + Enhancer.VERSION
        + ": classes enhanced by one call into the other (ADR 0036), so use the same version of both");
    }
    try {
      copy(classes, output.toPath());
    } catch (IOException | UncheckedIOException e) {
      throw new MojoExecutionException("could not copy " + classes + " to " + output, e);
    }
    List<Path> classpath = new ArrayList<>();
    try {
      for (String element : project.getCompileClasspathElements()) {
        if (!new File(element).getAbsoluteFile().equals(classes.toFile().getAbsoluteFile())) {
          classpath.add(new File(element).toPath());
        }
      }
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("no compile classpath", e);
    }
    Enhancer.Report report = new Enhancer(classpath).enhance(output.toPath());
    getLog().info("j2act: " + report);
    project.getBuild().setOutputDirectory(output.getAbsolutePath());
  }

  /**
   * Makes `to` hold what `from` holds: a file newer there is copied again, and one gone there goes. An enhanced copy is
   * newer than what it was made from, so it stays as long as that does not change.
   */
  static void copy(Path from, Path to) throws IOException {
    Set<Path> present = new HashSet<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(from)) {
      files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
    }
    for (Path file : files) {
      Path relative = from.relativize(file);
      present.add(relative);
      Path target = to.resolve(relative);
      if (!Files.exists(target) || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(target)) > 0) {
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    if (Files.isDirectory(to)) {
      List<Path> stale;
      try (Stream<Path> walk = Files.walk(to)) {
        stale = walk.filter(Files::isRegularFile).filter(p -> !present.contains(to.relativize(p))).collect(Collectors.toList());
      }
      for (Path file : stale) {
        Files.delete(file);
      }
    }
  }
}
