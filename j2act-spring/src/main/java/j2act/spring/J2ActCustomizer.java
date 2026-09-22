package j2act.spring;

import j2act.J2Act;

/** Declare as a bean to tune the mount: grace window, idle timeout, SSR budget, executor (ADR 0010, 0016). */
@FunctionalInterface
public interface J2ActCustomizer {

  void customize(J2Act.Builder builder);
}
