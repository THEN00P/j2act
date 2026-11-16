package j2act;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A getter given as a method reference, such as User::getAddress, naming one property of a class for
 * {@link Stored#at}. Serializable so that the property it names can be read off it (ADR 0035): pass a
 * reference to a public getter, not a lambda.
 */
@FunctionalInterface
public interface Getter<T, U> extends Function<T, U>, Serializable {
}
