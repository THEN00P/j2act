package j2act;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One instance per browser (ADR 0030), like Vaadin's @VaadinSessionScope: put it on a class
 * that extends {@link Store}. Every tab of the browser sees the same values, live: a write in
 * one tab re-renders the others that read it. It lives while any tab of the browser does; its
 * Retained State outlives the last tab and comes back to the next one.
 *
 * Its values live on the browser's own lane, so a write from a tab lands there a moment later,
 * and its computeds, queries and effects run once per browser, not once per tab. They cannot
 * read what differs between tabs, such as a tab store.
 *
 * Like {@link TabScoped}, it carries CDI's and Spring's meta-annotations, so the annotation alone
 * makes the class a bean wherever a container is present.
 */
@jakarta.enterprise.context.NormalScope
@org.springframework.stereotype.Component
@org.springframework.context.annotation.Scope(value = BrowserScoped.SPRING_SCOPE,
  proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BrowserScoped {

  /** The name j2act-spring registers the browser scope under. */
  String SPRING_SCOPE = "j2act-browser";
}
