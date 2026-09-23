package j2act;

import java.io.OutputStream;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * The file flavor of Mutation (ADR 0012). mutate(v) hands the browser a single-use URL;
 * the writer runs when the browser fetches it, on the request thread, streaming into
 * the response. The run is pending until the last byte is written.
 *
 * <pre>{@code
 * Download<String> export = download((String like, OutputStream out) -> users.writeCsv(like, out))
 *   .withFileName("users.csv")
 *   .withContentType("text/csv");
 * button("Export CSV").withCondDisabled(export.isPending()).onClick(e -> export.mutate(filter.get()));
 * }</pre>
 */
public final class Download<V> extends Mutation<V, Void> {

  /** Writes the file for the given variables. Never reads State: it runs off the lane. */
  @FunctionalInterface
  public interface Writer<V> {
    void write(V variables, OutputStream out) throws Exception;
  }

  final Writer<V> writer;
  String fileName = "download";
  String contentType = "application/octet-stream";

  Download(Writer<V> writer) {
    super(null);
    this.writer = writer;
  }

  /** The name the browser saves the file under. */
  public Download<V> withFileName(String fileName) {
    this.fileName = fileName;
    return this;
  }

  public Download<V> withContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  @Override public Download<V> withKey(Object... parts) {
    super.withKey(parts);
    return this;
  }

  /** Not supported: the browser runs the fetch, so a retry is another mutate(). */
  @Override public Download<V> withRetry(int retries, Duration backoff) {
    throw new UnsupportedOperationException("a download is fetched by the browser; call mutate() again to retry");
  }

  @Override public Download<V> withInvalidates(Object... keyPrefix) {
    super.withInvalidates(keyPrefix);
    return this;
  }

  @Override public Download<V> onSuccess(Consumer<? super Void> callback) {
    super.onSuccess(callback);
    return this;
  }

  @Override public Download<V> onError(Consumer<Throwable> callback) {
    super.onError(callback);
    return this;
  }
}
