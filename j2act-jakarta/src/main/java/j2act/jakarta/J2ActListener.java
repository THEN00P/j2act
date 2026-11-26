package j2act.jakarta;

import java.util.EnumSet;
import java.util.concurrent.Executor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
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

  /**
   * Tune the mount: identity via builder.withIdentity(exchange -> AuthCtx), grace window,
   * idle timeout, SSR budget, or a different executor.
   */
  protected void customize(J2Act.Builder builder) {
  }

  /**
   * A cluster from the context parameters (ADR 0037), when j2act.cluster.self names the URL other servers reach this
   * one at: its servers from j2act.cluster.members, a fixed list, j2act.cluster.seeds, a few to start from, or
   * j2act.cluster.dns with j2act.cluster.dns-port, and its secret from j2act.cluster.secret. For servers and leases in
   * the database, call builder.withCluster(...) in customize with j2act-retained-jdbc's DatabaseDiscovery.
   */
  private static void cluster(ServletContext context, J2Act.Builder builder) {
    String self = context.getInitParameter("j2act.cluster.self");
    if (self == null) {
      return;
    }
    j2act.cluster.Cluster.Builder cluster = j2act.cluster.Cluster.builder().self(self);
    String members = context.getInitParameter("j2act.cluster.members");
    String seeds = context.getInitParameter("j2act.cluster.seeds");
    String dns = context.getInitParameter("j2act.cluster.dns");
    if (members != null) {
      cluster.discovery(new j2act.cluster.StaticDiscovery(members.trim().split("\\s*,\\s*")));
    } else if (seeds != null) {
      cluster.discovery(new j2act.cluster.SeedDiscovery(seeds.trim().split("\\s*,\\s*")));
    } else if (dns != null) {
      String port = context.getInitParameter("j2act.cluster.dns-port");
      String scheme = context.getInitParameter("j2act.cluster.dns-scheme");
      String path = context.getInitParameter("j2act.cluster.dns-path");
      cluster.discovery(new j2act.cluster.DnsDiscovery(dns, scheme == null ? "http" : scheme,
        port == null ? 8080 : Integer.parseInt(port.trim()), path == null ? context.getContextPath() : path));
    } else {
      throw new IllegalStateException("j2act.cluster.self is set, but not how its servers find each other:"
        + " j2act.cluster.members, j2act.cluster.seeds or j2act.cluster.dns (ADR 0037)");
    }
    cluster.secret(context.getInitParameter("j2act.cluster.secret"));
    builder.withCluster(cluster.build());
  }

  @Override public void contextInitialized(ServletContextEvent event) {
    ServletContext context = event.getServletContext();
    PageResolver resolver = router();
    J2Act.Builder builder = J2Act.builder(resolver)
      .withContextPath(context.getContextPath())
      .withMembersInjector(CdiSupport.membersInjector());
    java.util.function.Function<Class<?>, Object> stores = CdiSupport.storeLookup();
    if (stores != null) {
      builder.withStoreLookup(stores);
    }
    Executor executor = ManagedExecutors.lookup();
    if (executor != null) {
      builder.withExecutor(CdiSupport.withRequestContext(executor));
      LOG.log(System.Logger.Level.INFO, "j2act runs on the container's ManagedExecutorService");
      // Its size is the container's configuration, which a ManagedExecutorService does not say (ADR 0037).
      String share = context.getInitParameter("j2act.blockingWork");
      if (share != null) {
        try {
          builder.withBlockingWork(Integer.parseInt(share.trim()));
        } catch (NumberFormatException e) {
          LOG.log(System.Logger.Level.WARNING, "j2act.blockingWork is not a number: " + share);
        }
      }
    } else {
      LOG.log(System.Logger.Level.INFO, "no ManagedExecutorService found; j2act uses its own pool");
    }
    try {
      builder.withJson(new JsonbBinding());
    } catch (LinkageError | RuntimeException e) {
      LOG.log(System.Logger.Level.INFO, "no JSON-B provider; client modules use the basic JSON binding");
    }
    cluster(context, builder);
    customize(builder);
    j2Act = builder.build();
    context.setAttribute(ATTRIBUTE, j2Act);

    FilterRegistration.Dynamic filter = context.addFilter("j2act", new J2ActFilter(j2Act, resolver));
    // A page streams its loading(...) boundaries without holding a thread (ADR 0031).
    filter.setAsyncSupported(true);
    filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");

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
