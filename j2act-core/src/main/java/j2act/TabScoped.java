package j2act;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One instance per tab (ADR 0030), like Vaadin's @UIScope or a Blazor scoped service: put it on
 * a class that extends {@link Store}. It ends with its tab, on unload, idle timeout or pause, and
 * a reload starts a new one.
 *
 * It carries CDI's and Spring's meta-annotations, so the annotation alone makes the class a
 * bean: CDI discovers it in any bean archive, and Spring's component scanning finds it in the
 * app's packages as it finds a @Service. Where either container is missing, the JVM drops its
 * meta-annotation and nothing else changes.
 */
@jakarta.enterprise.context.NormalScope
@org.springframework.stereotype.Component
@org.springframework.context.annotation.Scope(value = TabScoped.SPRING_SCOPE,
  proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TabScoped {

  /** The name j2act-spring registers the tab scope under. */
  String SPRING_SCOPE = "j2act-tab";
}
