package j2act;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The file flavor of Mutation (ADR 0006). mutate(file) with the UploadFile from a file
 * input's change event: the browser sends the bytes in resumable chunks, restrictions are
 * enforced on the server, and the file lands under withTarget with withNaming's name.
 * Pending until stored; progress() tracks the bytes received.
 *
 * <pre>{@code
 * Upload avatar = upload()
 *   .withTarget("/var/app/avatars")
 *   .withNaming((original, ctx) -> ctx.username() + "_" + ctx.timestamp() + "_" + original)
 *   .withAccept("image/*")
 *   .withMaxFileSize("10MB")
 *   .onSuccess(ref -> users.updateAvatar(userId, ref.path()));
 * input().withType("file").withAccept("image/*").onChange(e -> avatar.mutate(e.file()));
 * }</pre>
 */
public final class Upload extends Mutation<UploadFile, UploadRef> {

  /** Unless withMaxFileSize says otherwise. */
  public static final long DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024;

  Path target;
  BiFunction<String, UploadCtx, String> naming = (original, ctx) -> ctx.timestamp() + "_" + ctx.id() + "_" + original;
  final List<String> accept = new ArrayList<>();
  long maxFileSize = DEFAULT_MAX_FILE_SIZE;

  Upload() {
    super(null);
  }

  /** The directory finished files are moved into. Without one they stay framework-owned; see UploadRef.path(). */
  public Upload withTarget(Path directory) {
    this.target = directory;
    return this;
  }

  public Upload withTarget(String directory) {
    return withTarget(Paths.get(directory));
  }

  /**
   * The stored file name from the sanitized original name and the context. The result
   * must stay inside the target directory, or the upload fails.
   */
  public Upload withNaming(BiFunction<String, UploadCtx, String> naming) {
    this.naming = naming;
    return this;
  }

  /** Allowed types, as in the accept attribute: "image/*", "text/csv", ".pdf". Checked on the server. */
  public Upload withAccept(String... types) {
    accept.addAll(Arrays.asList(types));
    return this;
  }

  public Upload withMaxFileSize(long bytes) {
    this.maxFileSize = bytes;
    return this;
  }

  /** A size like "512KB", "10MB" or "1GB" (binary units). */
  public Upload withMaxFileSize(String size) {
    return withMaxFileSize(Uploads.parseSize(size));
  }

  /** Percent of the file's bytes the server has, 0 to 100. */
  public int progress() {
    return cell == null ? 0 : ((MutationCell) cell).read().progress;
  }

  @Override public Upload withKey(Object... parts) {
    super.withKey(parts);
    return this;
  }

  /** Not supported: the browser resumes failed chunks itself, so a retry is another mutate(). */
  @Override public Upload withRetry(int retries, Duration backoff) {
    throw new UnsupportedOperationException("an upload resumes its chunks itself; call mutate() again to retry");
  }

  @Override public Upload withInvalidates(Object... keyPrefix) {
    super.withInvalidates(keyPrefix);
    return this;
  }

  @Override public Upload onSuccess(Consumer<? super UploadRef> callback) {
    super.onSuccess(callback);
    return this;
  }

  @Override public Upload onError(Consumer<Throwable> callback) {
    super.onError(callback);
    return this;
  }
}
