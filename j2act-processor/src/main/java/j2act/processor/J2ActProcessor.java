package j2act.processor;

import java.util.Collections;
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
 * JavacSource and EcjSource. It also writes the TS types of client modules (ADR 0022,
 * ClientTypes). It claims no annotations.
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
  private ClientTypes clientTypes;
  private boolean tokenWritten;

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public Set<String> getSupportedOptions() {
    return Collections.singleton(ClientTypes.JSON_OPTION);
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
    clientTypes = new ClientTypes(env, source);
  }

  @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    if (!tokenWritten && !round.getRootElements().isEmpty()) {
      tokenWritten = true;
      clientTypes.project = DevToken.write(processingEnv.getFiler(), round.getRootElements().iterator().next());
    }
    for (Element root : round.getRootElements()) {
      if (root instanceof TypeElement) {
        try {
          if (checks != null) {
            checks.check((TypeElement) root);
          }
          clientTypes.generate((TypeElement) root);
        } catch (RuntimeException e) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
            "j2act: build-time processing failed on this type: " + e, root);
        }
      }
    }
    return false;
  }
}
