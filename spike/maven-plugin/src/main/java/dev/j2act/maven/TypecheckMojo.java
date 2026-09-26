package dev.j2act.maven;

import java.io.File;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * SPIKE: type-checks the TypeScript client modules with tsc in the test phase, so a type error
 * fails `mvn package` and `mvn verify` like a failing test, but never compiling or running the
 * app. Eclipse skips it (m2e metadata): the editor's TypeScript support shows the errors there.
 */
@Mojo(name = "typecheck", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class TypecheckMojo extends BundleMojo {

  @Parameter(defaultValue = "${project.basedir}", readonly = true)
  private File projectDir;

  @Parameter(property = "j2act.typecheck.skip", defaultValue = "false")
  private boolean skipTypecheck;

  @Override public void execute() throws MojoExecutionException {
    if (skipTypecheck || !new File(projectDir, "tsconfig.json").isFile()) {
      return;
    }
    exec(projectDir, List.of("tsc", "-p", "tsconfig.json", "--noEmit"));
  }
}
