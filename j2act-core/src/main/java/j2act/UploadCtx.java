package j2act;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** What a naming function may build a stored file name from; every value is filename-safe. */
public final class UploadCtx {

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final AuthCtx auth;
  private final Instant now;
  private final String id;

  UploadCtx(AuthCtx auth, Instant now, String id) {
    this.auth = auth;
    this.now = now;
    this.id = id;
  }

  /** The session's identity. */
  public AuthCtx auth() {
    return auth;
  }

  /** The principal name, sanitized, or "anonymous". */
  public String username() {
    return auth.isAnonymous() ? "anonymous" : Uploads.sanitize(auth.name());
  }

  /** UTC completion time as yyyyMMdd-HHmmss. */
  public String timestamp() {
    return STAMP.format(now);
  }

  /** A random id, unique per upload. */
  public String id() {
    return id;
  }
}
