package j2act.processor;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * SPIKE: writes META-INF/j2act/dev.json into the class output, naming the project folder
 * that holds package.json. The processor runs in every build, the IDE's included, so the
 * runtime can find the project wherever the classes were copied to (an exploded WAR a WTP
 * server published, for one). Only a folder that exists on the running machine turns dev
 * mode on; the Gradle and Maven plugins leave the token out of archives.
 */
final class DevToken {

  static final String PATH = "META-INF/j2act/dev.json";

  private DevToken() {
  }

  /**
   * Writes the token and returns the project folder, or null when there is none. The token names
   * one type it came from, as Gradle's incremental compile wants of an isolating processor.
   */
  static File write(Filer filer, Element origin) {
    try {
      FileObject token = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH, origin);
      URI uri = token.toUri();
      if (!"file".equals(uri.getScheme())) {
        return null;
      }
      File project = projectOf(new File(uri));
      if (project == null) {
        return null;
      }
      try (Writer out = token.openWriter()) {
        out.write("{\"project\":\"" + project.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}\n");
      }
      return project;
    } catch (IOException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
      // No token, no dev mode: the build goes on.
      return null;
    }
  }

  /** The nearest folder above the class output with a package.json and a Maven or Gradle build file. */
  static File projectOf(File start) {
    for (File dir = start.getParentFile(); dir != null; dir = dir.getParentFile()) {
      if (new File(dir, "package.json").isFile() && (new File(dir, "pom.xml").isFile()
        || new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile())) {
        return dir;
      }
    }
    return null;
  }
}
