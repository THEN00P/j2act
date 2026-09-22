package j2act.jakarta;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.InjectionTarget;

import j2act.MembersInjector;

/**
 * Optional CDI integration. Every entry point checks for CDI first, so the adapter
 * runs unchanged on a plain servlet container.
 */
final class CdiSupport {

  private static final System.Logger LOG = System.getLogger("j2act.jakarta");

  private CdiSupport() {
  }

  /**
   * Injects components as non-contextual instances: @Inject, and on WildFly also
   * @PersistenceContext and @Resource. Inject @ApplicationScoped or @RequestScoped beans;
   * a @Dependent bean would be created again for every fresh component object.
   */
  static MembersInjector membersInjector() {
    BeanManager beans = beanManager();
    if (beans == null) {
      return MembersInjector.NONE;
    }
    Map<Class<?>, InjectionTarget<Object>> targets = new ConcurrentHashMap<>();
    return component -> {
      InjectionTarget<Object> target = targets.computeIfAbsent(component.getClass(), type -> injectionTarget(beans, type));
      CreationalContext<Object> context = beans.createCreationalContext(null);
      target.inject(component, context);
    };
  }

  /**
   * Wraps each executor task in an activated CDI request context, so @RequestScoped
   * beans work in handlers and query loaders. One lane drain is one request.
   */
  static Executor withRequestContext(Executor executor) {
    if (beanManager() == null) {
      return executor;
    }
    return task -> executor.execute(() -> {
      RequestContextController requestContext = CDI.current().select(RequestContextController.class).get();
      boolean activated = requestContext.activate();
      try {
        task.run();
      } finally {
        if (activated) {
          requestContext.deactivate();
        }
      }
    });
  }

  @SuppressWarnings("unchecked")
  private static InjectionTarget<Object> injectionTarget(BeanManager beans, Class<?> type) {
    return beans.getInjectionTargetFactory(beans.createAnnotatedType((Class<Object>) type)).createInjectionTarget(null);
  }

  private static BeanManager beanManager() {
    try {
      return CDI.current().getBeanManager();
    } catch (IllegalStateException | NoClassDefFoundError e) {
      LOG.log(System.Logger.Level.DEBUG, "no CDI container; components are not injected", e);
      return null;
    }
  }
}
