package j2act.spring;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

import j2act.StoreScope;
import j2act.TabScoped;

/**
 * The j2act-tab scope behind @TabScoped (ADR 0030): one bean per tab, the tab current on the
 * calling thread. Components get a scoped proxy, so a call from any tab reaches that tab's bean.
 */
final class TabStoreScope implements Scope {

  @Override public Object get(String name, ObjectFactory<?> factory) {
    return StoreScope.get(TabScoped.class, name, factory::getObject, null);
  }

  @Override public Object remove(String name) {
    return StoreScope.remove(TabScoped.class, name);
  }

  @Override public void registerDestructionCallback(String name, Runnable callback) {
    StoreScope.registerDestruction(TabScoped.class, name, callback);
  }

  @Override public Object resolveContextualObject(String key) {
    return null;
  }

  @Override public String getConversationId() {
    return StoreScope.id(TabScoped.class);
  }
}
