package j2act.spring;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

import java.lang.annotation.Annotation;

import j2act.StoreScope;

/**
 * The j2act-tab and j2act-browser scopes behind @TabScoped and @BrowserScoped (ADR 0030): one
 * bean per tab, or per browser, of the tab current on the calling thread. Components get a
 * scoped proxy, so a call from any tab reaches that tab's bean.
 */
final class StoreBeanScope implements Scope {

  private final Class<? extends Annotation> scope;

  StoreBeanScope(Class<? extends Annotation> scope) {
    this.scope = scope;
  }

  @Override public Object get(String name, ObjectFactory<?> factory) {
    return StoreScope.get(scope, name, factory::getObject, null);
  }

  @Override public Object remove(String name) {
    return StoreScope.remove(scope, name);
  }

  @Override public void registerDestructionCallback(String name, Runnable callback) {
    StoreScope.registerDestruction(scope, name, callback);
  }

  @Override public Object resolveContextualObject(String key) {
    return null;
  }

  @Override public String getConversationId() {
    return StoreScope.id(scope);
  }
}
