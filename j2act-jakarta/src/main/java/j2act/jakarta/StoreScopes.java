package j2act.jakarta;

import java.lang.annotation.Annotation;

import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

import j2act.StoreScope;
import j2act.TabScoped;

/**
 * Adds the context behind @TabScoped (ADR 0030): one bean per tab, the tab current on the
 * calling thread. The annotation carries @NormalScope itself, so CDI discovers stores in any
 * bean archive; this only supplies the context. Components get a client proxy.
 */
public final class StoreScopes implements Extension {

  void addContexts(@Observes AfterBeanDiscovery event) {
    event.addContext(new TabContext());
  }

  static final class TabContext implements AlterableContext {

    @Override public Class<? extends Annotation> getScope() {
      return TabScoped.class;
    }

    @Override public <T> T get(Contextual<T> bean, CreationalContext<T> context) {
      return StoreScope.get(TabScoped.class, bean, () -> bean.create(context), instance -> bean.destroy(instance, context));
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> bean) {
      return (T) StoreScope.getIfExists(TabScoped.class, bean);
    }

    @Override public boolean isActive() {
      return StoreScope.isActive(TabScoped.class);
    }

    @Override public void destroy(Contextual<?> bean) {
      StoreScope.remove(TabScoped.class, bean);
    }
  }
}
