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

  /**
   * Enhances target/classes itself, so that what runs from it runs enhanced too: tests after this goal, the app run
   * from the build, an exploded WAR made from it. For profiling and for trying the enhanced app in development; what an
   * IDE compiles there afterwards is plain again, and its hot code replace cannot swap a plain class over an enhanced
   * one on a JVM without enhanced redefinition (ADR 0036).
   */
  @Parameter(property = "j2act.enhance.classFolders", defaultValue = "false")
  private boolean classFolders;

  /**
   * Packages, by prefix such as com.example.legacy., whose classes stay plain store classes: a store reads them whole
   * instead of field by field (ADR 0035, 0036).
   */
  @Parameter
  private List<String> trackExcludes = new ArrayList<>();

  /** Leaves the classes plain: the app runs the same, re-running whole views. */
  @Parameter(property = "j2act.enhance.skip", defaultValue = "false")
  private boolean skip;

  @Override public void execute() throws MojoExecutionException {
    Path classes = new File(project.getBuild().getOutputDirectory()).toPath();
    if (!Files.isDirectory(classes)) {
      return;
    }
    // Maven does not compile classes again whose sources did not change, so they stay enhanced after the flag is off.
    File marker = new File(project.getBuild().getDirectory(), "j2act-enhanced-class-folders");
    if (!classFolders && marker.isFile()) {
      getLog().warn("j2act: target/classes still holds classes enhanced by an earlier -Dj2act.enhance.classFolders"
        + " build; run mvn clean for plain ones");
    }
    if (skip) {
      getLog().info("j2act: not enhanced (j2act.enhance.skip)");
      if (!classFolders && output.isDirectory()) {
        // An earlier build packaged an enhanced copy, and a jar made from it is newer than the plain classes, so the
        // jar plugin would keep it: a plain copy, newer than that jar, is packaged instead.
        refresh(classes, output.toPath(), "plain");
        project.getBuild().setOutputDirectory(output.getAbsolutePath());
      }
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
    Path target = classFolders ? classes : output.toPath();
    if (!classFolders) {
      refresh(classes, target, enhancerIdentity() + "\ntrackExcludes " + new java.util.TreeSet<>(trackExcludes));
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
    if (classFolders) {
      try {
        Files.createDirectories(marker.getParentFile().toPath());
        Files.write(marker.toPath(), new byte[0]);
      } catch (IOException e) {
        throw new MojoExecutionException("could not write " + marker, e);
      }
    }
    Enhancer.Report report = new Enhancer(classpath).withTrackExcludes(trackExcludes).enhance(target);
    getLog().info("j2act: " + report + (classFolders ? ", in " + classes : ""));
    if (!classFolders) {
      project.getBuild().setOutputDirectory(output.getAbsolutePath());
    }
  }

  /**
   * Makes `target` the copy of `classes` that `made` makes, `plain` for none. A copy made otherwise, by another enhancer
   * (a snapshot rebuilt at the same version), with other packages left untracked or plain, starts over, since what is
   * stamped is never enhanced again. What made it is written beside the copy, not in it, which is packaged.
   */
  private void refresh(Path classes, Path target, String made) throws MojoExecutionException {
    try {
      Path stamp = target.resolveSibling(target.getFileName() + ".enhancer");
      if (Files.isDirectory(target) && !(Files.isRegularFile(stamp)
        && new String(Files.readAllBytes(stamp), java.nio.charset.StandardCharsets.UTF_8).equals(made))) {
        clear(target);
      }
      copy(classes, target);
      Files.write(stamp, made.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException | UncheckedIOException e) {
      throw new MojoExecutionException("could not copy " + classes + " to " + target, e);
    }
  }

  /** The enhancer's version and the bytes of its jar, so a rebuilt snapshot counts as another enhancer. */
  static String enhancerIdentity() {
    try {
      java.net.URL source = Enhancer.class.getProtectionDomain().getCodeSource().getLocation();
      Path jar = Path.of(source.toURI());
      if (Files.isRegularFile(jar)) {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(Files.readAllBytes(jar))) {
          hex.append(String.format("%02x", b));
        }
        return Enhancer.VERSION + " " + hex;
      }
    } catch (Exception e) {
      // Only the version, then.
    }
    return Enhancer.VERSION;
  }

  private static void clear(Path dir) throws IOException {
    List<Path> all;
    try (Stream<Path> walk = Files.walk(dir)) {
      all = walk.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList());
    }
    for (Path p : all) {
      if (!p.equals(dir)) {
        Files.delete(p);
      }
    }
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
