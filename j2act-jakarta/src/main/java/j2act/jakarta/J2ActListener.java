package j2act.jakarta;

import java.util.EnumSet;
import java.util.concurrent.Executor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import j2act.J2Act;
import j2act.PageResolver;

/**
 * Mounts j2act in a Jakarta EE 10 web app. Subclass it, annotate with @WebListener
 * and return your routes:
 *
 * <pre>{@code
 * @WebListener
 * public class App extends J2ActListener {
 *   protected PageResolver router() {
 *     return routes(page("/", HomePage.class));
 *   }
 * }
 * }</pre>
 *
 * Pages are served by a filter mapped after the app's own filters, so the host's
 * security filters run first. Components get CDI injection when CDI is present, and
 * lanes run on the container's ManagedExecutorService with a CDI request context
 * active, so @PersistenceContext, JNDI and @RequestScoped beans work in handlers
 * (ADR 0004, 0017).
 */
public abstract class J2ActListener implements ServletContextListener {

  /** ServletContext attribute holding the running J2Act, for tests and diagnostics. */
  public static final String ATTRIBUTE = J2Act.class.getName();

  static final String SOCKET_PATH = "/_j2act/ws";
  private static final System.Logger LOG = System.getLogger("j2act.jakarta");

  private J2Act j2Act;

  /** The app's routes, e.g. {@code routes(page("/", HomePage.class))}. */
  protected abstract PageResolver router();

  /** Tune the mount: grace window, idle timeout, SSR budget, or a different executor. */
  protected void customize(J2Act.Builder builder) {
  }

  @Override public void contextInitialized(ServletContextEvent event) {
    ServletContext context = event.getServletContext();
    PageResolver resolver = router();
    J2Act.Builder builder = J2Act.builder(resolver)
      .withContextPath(context.getContextPath())
      .withMembersInjector(CdiSupport.membersInjector());
    Executor executor = ManagedExecutors.lookup();
    if (executor != null) {
      builder.withExecutor(CdiSupport.withRequestContext(executor));
      LOG.log(System.Logger.Level.INFO, "j2act runs on the container's ManagedExecutorService");
    } else {
      LOG.log(System.Logger.Level.INFO, "no ManagedExecutorService found; j2act uses its own pool");
    }
    customize(builder);
    j2Act = builder.build();
    context.setAttribute(ATTRIBUTE, j2Act);

    context.addFilter("j2act", new J2ActFilter(j2Act, resolver))
      .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");

    ServerContainer sockets = (ServerContainer) context.getAttribute(ServerContainer.class.getName());
    if (sockets == null) {
      throw new IllegalStateException("no jakarta.websocket ServerContainer; is WebSocket support enabled?");
    }
    try {
      sockets.addEndpoint(ServerEndpointConfig.Builder
        .create(J2ActEndpoint.class, SOCKET_PATH)
        .configurator(new J2ActConfigurator(j2Act))
        .build());
    } catch (DeploymentException e) {
      throw new IllegalStateException("cannot register the j2act socket at " + SOCKET_PATH, e);
    }
  }

  @Override public void contextDestroyed(ServletContextEvent event) {
    if (j2Act != null) {
      j2Act.close();
    }
  }
}
