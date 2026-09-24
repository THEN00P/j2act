package j2act.processor;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * The build-time checks of ADR 0008, the same under javac (Maven, Gradle, IntelliJ) and
 * ECJ (Eclipse, VS Code): raw HTML, script URLs, query loaders that capture locals
 * (ADR 0020) and components repeated without keys (ADR 0019). All are warnings, and
 * all read method bodies, which the standard processor API does not expose; see
 * JavacSource and EcjSource. It claims no annotations and generates nothing.
 *
 * <pre>{@code
 * <annotationProcessorPaths>
 *   <path>
 *     <groupId>dev.j2act</groupId>
 *     <artifactId>j2act-processor</artifactId>
 *     <version>${j2act.version}</version>
 *   </path>
 * </annotationProcessorPaths>
 * }</pre>
 */
@SupportedAnnotationTypes("*")
public final class J2ActProcessor extends AbstractProcessor {

  private Checks checks;

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    // ECJ first: under Eclipse's class loaders com.sun.source may not even load.
    Source source = EcjSource.of(env);
    if (source == null) {
      try {
        source = JavacSource.of(env);
      } catch (LinkageError e) {
        source = null;
      }
    }
    if (source == null) {
      env.getMessager().printMessage(Diagnostic.Kind.WARNING,
        "j2act: build-time checks skipped, " + env.getClass().getName() + " is neither javac nor ECJ");
    } else {
      checks = new Checks(env, source);
    }
  }

  @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    if (checks == null) {
      return false;
    }
    for (Element root : round.getRootElements()) {
      if (root instanceof TypeElement) {
        try {
          checks.check((TypeElement) root);
        } catch (RuntimeException e) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
            "j2act: build-time checks failed on this type: " + e, root);
        }
      }
    }
    return false;
  }
}
