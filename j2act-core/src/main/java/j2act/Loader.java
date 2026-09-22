package j2act;

/** A query loader. Runs on the executor; State read inside it becomes a dependency (ADR 0020). */
@FunctionalInterface
public interface Loader<T> {

  T load() throws Exception;
}
