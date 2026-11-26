package j2act.spring;

import java.util.concurrent.Executor;

import javax.sql.DataSource;

import jakarta.servlet.ServletContext;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import j2act.J2Act;
import j2act.JsonBinding;
import j2act.PageResolver;
import j2act.RetainedStateStorage;
import j2act.BrowserScoped;
import j2act.TabScoped;
import j2act.jdbc.JdbcRetainedStateStorage;

/**
 * Mounts j2act on Spring Boot 3+ when the app declares a PageResolver bean (e.g. Routes.routes(...)).
 * DI goes through autowireBean, so @Autowired, @Inject and @PersistenceContext work in
 * components; lanes and query runs use the app's task executor (ADR 0004, 0017). Retained State
 * goes to a RetainedStateStorage bean if there is one, and with j2act-retained-jdbc on the
 * classpath to the app's DataSource (ADR 0026). @TabScoped and @BrowserScoped stores are beans
 * in the j2act-tab and j2act-browser scopes, which component scanning finds in the app's
 * packages (ADR 0030).
 */
@AutoConfiguration(afterName = {
  // Boot 3, then Boot 4, which moved it.
  "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
  "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(PageResolver.class)
public class J2ActAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public J2Act j2Act(
    PageResolver resolver,
    AutowireCapableBeanFactory beans,
    ObjectProvider<ServletContext> servletContext,
    @Qualifier("applicationTaskExecutor") ObjectProvider<Executor> taskExecutor,
    ObjectProvider<J2ActCustomizer> customizers,
    ObjectProvider<J2ActIdentity> identity,
    ObjectProvider<JsonBinding> json,
    ObjectProvider<RetainedStateStorage> retainedStorage,
    ObjectProvider<j2act.cluster.Cluster> cluster,
    ListableBeanFactory beanFactory
  ) {
    ServletContext context = servletContext.getIfAvailable();
    J2Act.Builder builder = J2Act.builder(resolver)
      .withMembersInjector(beans::autowireBean)
      .withStoreLookup(beanFactory::getBean)
      .withContextPath(context == null ? "" : context.getContextPath());
    Executor executor = taskExecutor.getIfAvailable();
    if (executor != null) {
      builder.withExecutor(executor);
      int share = blockingShare(executor);
      if (share > 0) {
        builder.withBlockingWork(share);
      }
    }
    identity.ifAvailable(builder::withIdentity);
    retainedStorage.ifAvailable(builder::withRetainedStateStorage);
    cluster.ifAvailable(builder::withCluster);
    JsonBinding binding = json.getIfAvailable(() -> JacksonBinding.find(beanFactory, getClass().getClassLoader()));
    if (binding != null) {
      builder.withJson(binding);
    }
    customizers.orderedStream().forEach(c -> c.customize(builder));
    return builder.build();
  }

  /**
   * How much of Spring's task executor blocking work may use (ADR 0037): three quarters of a thread pool, which grows
   * past its core only once its queue is full, and no limit for one that starts a thread per task, such as Boot's with
   * virtual threads; 0 when j2act cannot tell.
   */
  static int blockingShare(Executor executor) {
    if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) {
      org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool =
        (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) executor;
      int threads = pool.getQueueCapacity() == Integer.MAX_VALUE ? pool.getCorePoolSize() : pool.getMaxPoolSize();
      return Math.max(1, threads * 3 / 4);
    }
    if (executor instanceof org.springframework.core.task.SimpleAsyncTaskExecutor
        && ((org.springframework.core.task.SimpleAsyncTaskExecutor) executor).getConcurrencyLimit()
          == org.springframework.core.task.SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY) {
      return Integer.MAX_VALUE;
    }
    return 0;
  }

  /** The scopes @TabScoped and @BrowserScoped name: one store bean per tab, or per browser (ADR 0030). */
  @Bean
  static BeanFactoryPostProcessor j2ActStoreScopes() {
    return factory -> {
      factory.registerScope(TabScoped.SPRING_SCOPE, new StoreBeanScope(TabScoped.class));
      factory.registerScope(BrowserScoped.SPRING_SCOPE, new StoreBeanScope(BrowserScoped.class));
    };
  }

  /**
   * Snapshots go to the app's own DataSource, so its credentials stay in Spring's configuration.
   * The table comes from j2act/jdbc/schema-<database>.sql through the app's migrations. Declare a
   * RetainedStateStorage bean instead for another DataSource or table.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(JdbcRetainedStateStorage.class)
  @ConditionalOnSingleCandidate(DataSource.class)
  static class RetainedJdbc {

    @Bean
    @ConditionalOnMissingBean
    RetainedStateStorage j2ActRetainedStateStorage(DataSource dataSource) {
      return JdbcRetainedStateStorage.of(dataSource);
    }
  }

  /**
   * A cluster from the application's properties (ADR 0037), when j2act.cluster.self names the URL other servers reach
   * this one at. Its servers come from j2act.cluster.members, a fixed list, j2act.cluster.seeds, a few to start from,
   * or j2act.cluster.dns with j2act.cluster.dns-port, and otherwise from the app's DataSource, which then keeps the
   * secret and the leases too. j2act.cluster.secret sets the secret where no database keeps it. Declare a Cluster bean
   * instead for anything else.
   */
  @Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("j2act.cluster.self")
  static class ClusterFromProperties {

    @Bean
    @ConditionalOnMissingBean
    // Its tables are the app's: they exist once Spring ran the schema scripts.
    @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    j2act.cluster.Cluster j2ActCluster(org.springframework.core.env.Environment env, ObjectProvider<DataSource> data) {
      j2act.cluster.Cluster.Builder cluster = j2act.cluster.Cluster.builder().self(env.getRequiredProperty("j2act.cluster.self"));
      String members = env.getProperty("j2act.cluster.members");
      String seeds = env.getProperty("j2act.cluster.seeds");
      String dns = env.getProperty("j2act.cluster.dns");
      DataSource dataSource = data.getIfUnique();
      if (members != null) {
        cluster.discovery(new j2act.cluster.StaticDiscovery(members.split("\\s*,\\s*")));
      } else if (seeds != null) {
        cluster.discovery(new j2act.cluster.SeedDiscovery(seeds.split("\\s*,\\s*")));
      } else if (dns != null) {
        cluster.discovery(new j2act.cluster.DnsDiscovery(dns, env.getProperty("j2act.cluster.dns-scheme", "http"),
          env.getProperty("j2act.cluster.dns-port", Integer.class, 8080), env.getProperty("j2act.cluster.dns-path", "")));
      } else if (dataSource != null && hasJdbc()) {
        cluster.discovery(j2act.jdbc.DatabaseDiscovery.of(dataSource));
      } else {
        throw new IllegalStateException("j2act.cluster.self is set, but not how its servers find each other:"
          + " j2act.cluster.members, j2act.cluster.seeds, j2act.cluster.dns, or a DataSource with j2act-retained-jdbc"
          + " (ADR 0037)");
      }
      if (dataSource != null && hasJdbc()) {
        cluster.leases(j2act.jdbc.DatabaseLeases.of(dataSource));
      }
      String secret = env.getProperty("j2act.cluster.secret");
      if (secret != null) {
        cluster.secret(secret);
      }
      return cluster.build();
    }

    private static boolean hasJdbc() {
      try {
        Class.forName("j2act.jdbc.DatabaseDiscovery", false, ClusterFromProperties.class.getClassLoader());
        return true;
      } catch (ClassNotFoundException e) {
        return false;
      }
    }
  }

  /**
   * Hands the shared stores this server holds to the cluster's other servers as the application stops, before its web
   * server does, while the others still reach it over its socket (ADR 0037). Nothing without a cluster.
   */
  @Bean
  public org.springframework.context.SmartLifecycle j2ActLeaveCluster(J2Act j2Act) {
    return new org.springframework.context.SmartLifecycle() {
      private volatile boolean running;

      @Override public void start() {
        running = true;
      }

      @Override public void stop() {
        running = false;
        j2Act.leaveCluster();
      }

      @Override public boolean isRunning() {
        return running;
      }
    };
  }

  @Bean
  public J2ActHandlerMapping j2ActHandlerMapping(J2Act j2Act, PageResolver resolver) {
    return new J2ActHandlerMapping(j2Act, resolver);
  }

  @Bean
  public J2ActSocketHandler j2ActSocketHandler(J2Act j2Act) {
    return new J2ActSocketHandler(j2Act);
  }

  /** Same-origin only: Spring's default origin check stays on (ADR 0008). */
  @Configuration(proxyBeanMethods = false)
  @EnableWebSocket
  static class Sockets implements WebSocketConfigurer {

    private final J2ActSocketHandler handler;

    Sockets(J2ActSocketHandler handler) {
      this.handler = handler;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
      registry.addHandler(handler, "/_j2act/ws");
    }
  }
}
