package j2act.spring;

import java.util.concurrent.Executor;

import javax.sql.DataSource;

import jakarta.servlet.ServletContext;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
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
import j2act.jdbc.JdbcRetainedStateStorage;

/**
 * Mounts j2act on Spring Boot 3+ when the app declares a PageResolver bean (e.g. Routes.routes(...)).
 * DI goes through autowireBean, so @Autowired, @Inject and @PersistenceContext work in
 * components; lanes and query runs use the app's task executor (ADR 0004, 0017). Retained State
 * goes to a RetainedStateStorage bean if there is one, and with j2act-retained-jdbc on the
 * classpath to the app's DataSource (ADR 0026).
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
    ListableBeanFactory beanFactory
  ) {
    ServletContext context = servletContext.getIfAvailable();
    J2Act.Builder builder = J2Act.builder(resolver)
      .withMembersInjector(beans::autowireBean)
      .withContextPath(context == null ? "" : context.getContextPath());
    Executor executor = taskExecutor.getIfAvailable();
    if (executor != null) {
      builder.withExecutor(executor);
    }
    identity.ifAvailable(builder::withIdentity);
    retainedStorage.ifAvailable(builder::withRetainedStateStorage);
    JsonBinding binding = json.getIfAvailable(() -> JacksonBinding.find(beanFactory, getClass().getClassLoader()));
    if (binding != null) {
      builder.withJson(binding);
    }
    customizers.orderedStream().forEach(c -> c.customize(builder));
    return builder.build();
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
