package j2act;

/**
 * What a component's setup() returns (ADR 0031): called again whenever something it read
 * changes, it returns the component's one root element.
 */
@FunctionalInterface
public interface View {

  Tag<?> render();
}
