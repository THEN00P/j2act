package dev.j2act.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * SPIKE: Eclipse annotation processing for a Buildship project, which Buildship does not set up.
 * Writes what m2e does for Maven and the Eclipse language server does for Gradle: the processor
 * jars in .factorypath, APT on with a project-relative generated source folder (bin/, which Eclipse
 * projects already ignore), and annotation processing on in the compiler settings, keeping every
 * other setting. Runs as a Buildship synchronization task, so every Gradle refresh keeps it current.
 */
@DisableCachingByDefault(because = "writes Eclipse project files")
public abstract class EclipseAptTask extends DefaultTask {

  static final String GENERATED = "bin/generated-sources/annotations";

  @Classpath
  public abstract ConfigurableFileCollection getProcessorPath();

  @Internal
  public abstract DirectoryProperty getProjectDir();

  @TaskAction
  public void write() throws IOException {
    File project = getProjectDir().get().getAsFile();
    StringBuilder factory = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<factorypath>\n");
    for (File jar : getProcessorPath().getFiles()) {
      factory.append("    <factorypathentry kind=\"EXTJAR\" id=\"").append(xml(jar.getAbsolutePath()))
        .append("\" enabled=\"true\" runInBatchMode=\"false\"/>\n");
    }
    factory.append("</factorypath>\n");
    Files.write(new File(project, ".factorypath").toPath(), factory.toString().getBytes(StandardCharsets.UTF_8));

    File settings = new File(project, ".settings");
    settings.mkdirs();
    update(new File(settings, "org.eclipse.jdt.apt.core.prefs"), p -> {
      p.setProperty("eclipse.preferences.version", "1");
      p.setProperty("org.eclipse.jdt.apt.aptEnabled", "true");
      p.setProperty("org.eclipse.jdt.apt.reconcileEnabled", "true");
      // Project-relative: Eclipse reads an absolute Windows path as a folder named Users/... here.
      p.setProperty("org.eclipse.jdt.apt.genSrcDir", GENERATED);
      p.setProperty("org.eclipse.jdt.apt.genTestSrcDir", "bin/generated-test-sources/annotations");
    });
    update(new File(settings, "org.eclipse.jdt.core.prefs"), p -> {
      p.setProperty("eclipse.preferences.version", "1");
      p.setProperty("org.eclipse.jdt.core.compiler.processAnnotations", "enabled");
      // Parameter names in the class files, as javac -parameters: the runtime's fallback for
      // mount() prop names when a processor output is missing.
      p.setProperty("org.eclipse.jdt.core.compiler.codegen.methodParameters", "generate");
    });
  }

  private interface Edit {
    void apply(Properties properties);
  }

  private static void update(File file, Edit edit) throws IOException {
    Properties properties = new Properties();
    if (file.isFile()) {
      try (InputStream in = Files.newInputStream(file.toPath())) {
        properties.load(in);
      }
    }
    edit.apply(properties);
    try (OutputStream out = Files.newOutputStream(file.toPath())) {
      properties.store(out, null);
    }
  }

  private static String xml(String text) {
    return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
  }
}
