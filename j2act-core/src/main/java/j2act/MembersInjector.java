package j2act;

/**
 * Host DI seam (ADR 0004): Spring autowireBean, CDI, or nothing. Called when a
 * component object is bound to its slot, before it renders.
 */
@FunctionalInterface
public interface MembersInjector {

  MembersInjector NONE = component -> { };

  void inject(Object component);
}
