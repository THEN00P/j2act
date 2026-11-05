package j2act;

import java.util.concurrent.CompletionStage;

/** The body of an async memo (ADR 0031): runs on the lane, its reads tracked, and returns the future to wait for. */
@FunctionalInterface
public interface AsyncSupplier<T> {

  CompletionStage<T> get() throws Exception;
}
