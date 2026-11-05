package j2act;

/** Blocking work for async(...) or retry(...): runs on the executor, and may throw. */
@FunctionalInterface
public interface Loader<T> {

  T load() throws Exception;
}
