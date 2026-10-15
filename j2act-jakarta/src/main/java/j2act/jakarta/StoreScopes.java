package j2act.jakarta;

import java.lang.annotation.Annotation;

import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

import j2act.BrowserScoped;
import j2act.StoreScope;
import j2act.TabScoped;

/**
 * Adds the contexts behind @TabScoped and @BrowserScoped (ADR 0030): one bean per tab, or per
 * browser, of the tab current on the calling thread. The annotations carry @NormalScope
 * themselves, so CDI discovers stores in any bean archive; this only supplies the contexts.
 * Components get a client proxy.
 */
public final class StoreScopes implements Extension {

  void addContexts(@Observes AfterBeanDiscovery event) {
    event.addContext(new StoreContext(TabScoped.class));
    event.addContext(new StoreContext(BrowserScoped.class));
  }

  static final class StoreContext implements AlterableContext {

    private final Class<? extends Annotation> scope;

    StoreContext(Class<? extends Annotation> scope) {
      this.scope = scope;
    }

    @Override public Class<? extends Annotation> getScope() {
      return scope;
    }

    @Override public <T> T get(Contextual<T> bean, CreationalContext<T> context) {
      return StoreScope.get(scope, bean, () -> bean.create(context), instance -> bean.destroy(instance, context));
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> bean) {
      return (T) StoreScope.getIfExists(scope, bean);
    }

    @Override public boolean isActive() {
      return StoreScope.isActive(scope);
    }

    @Override public void destroy(Contextual<?> bean) {
      StoreScope.remove(scope, bean);
    }
  }
}
