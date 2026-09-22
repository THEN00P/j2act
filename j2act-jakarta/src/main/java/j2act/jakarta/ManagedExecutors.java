package j2act.jakarta;

import java.util.concurrent.Executor;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Finds the container's default ManagedExecutorService (Jakarta Concurrency). Tasks on
 * it keep the app's class loader, JNDI and security context; plain threads on WildFly
 * lose them (ADR 0017). Looked up by JNDI so no Concurrency API is needed at compile time.
 */
final class ManagedExecutors {

  static final String DEFAULT = "java:comp/DefaultManagedExecutorService";

  private ManagedExecutors() {
  }

  /** The default managed executor, or null outside a full EE container. */
  static Executor lookup() {
    try {
      Object found = new InitialContext().lookup(DEFAULT);
      return found instanceof Executor ? (Executor) found : null;
    } catch (NamingException | RuntimeException e) {
      return null;
    }
  }
}
