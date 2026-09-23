package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * One upload in flight: a temp file the chunks append to, in order. Chunks arrive on
 * request threads, one at a time; the outcome commits to the Mutation on the lane.
 */
final class UploadSink {

  /** Largest chunk accepted; the client sends 512 KiB. */
  static final int MAX_CHUNK = 1024 * 1024;

  final String token;
  final Session session;
  private final MutationCell cell;
  private final Upload run;
  private final UploadFile file;
  private final long seq;
  private final Path temp;
  private long received;
  private int percent;
  private boolean closed;
  volatile long lastChunkAt;

  UploadSink(String token, Session session, MutationCell cell, Upload run, UploadFile file, long seq, Path temp) {
    this.token = token;
    this.session = session;
    this.cell = cell;
    this.run = run;
    this.file = file;
    this.seq = seq;
    this.temp = temp;
    this.lastChunkAt = session.engine.clock.millis();
  }

  /** Appends one chunk that starts at {@code offset}; a mismatch answers 409 with the offset to resume from. */
  synchronized ChunkResult accept(long offset, InputStream in) {
    if (closed) {
      return ChunkResult.error(404, "upload is over");
    }
    if (offset != received) {
      return ChunkResult.offset(409, received, false);
    }
    long allowed = Math.min(file.size() - received, MAX_CHUNK);
    long written = 0;
    boolean overflow = false;
    try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.APPEND)) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        if (written + n > allowed) {
          overflow = true;
          break;
        }
        out.write(buffer, 0, n);
        written += n;
      }
    } catch (IOException e) {
      // A dropped connection keeps what reached the disk; the client resumes from here.
      received += written;
      lastChunkAt = session.engine.clock.millis();
      return ChunkResult.offset(409, received, false);
    }
    if (overflow) {
      fail(new IllegalStateException("upload chunk went past the declared size or the chunk limit"));
      return ChunkResult.error(413, "too many bytes");
    }
    received += written;
    lastChunkAt = session.engine.clock.millis();
    int now = file.size() == 0 ? 100 : (int) (received * 100 / file.size());
    if (now != percent && received < file.size()) {
      percent = now;
      session.post(() -> cell.progress(seq, now));
    }
    if (received < file.size()) {
      return ChunkResult.offset(200, received, false);
    }
    return store();
  }

  /** Moves the complete file into place under the naming function's name. */
  private ChunkResult store() {
    closed = true;
    session.engine.uploads.remove(token);
    try {
      String original = Uploads.sanitize(file.name());
      UploadCtx ctx = new UploadCtx(session.currentAuth(), Instant.ofEpochMilli(session.engine.clock.millis()),
        session.engine.newSecret(9));
      Path dir = (run.target != null ? run.target : session.engine.uploadDir().resolve("done")).toAbsolutePath().normalize();
      Path dest = dir.resolve(run.naming.apply(original, ctx)).normalize();
      if (!dest.startsWith(dir) || dest.equals(dir)) {
        throw new SecurityException("upload name escapes the target directory: " + dest);
      }
      Files.createDirectories(dest.getParent());
      Files.move(temp, dest);
      if (run.target == null) {
        session.ownFile(dest);
      }
      UploadRef ref = new UploadRef(dest, original, received, contentType());
      session.post(() -> cell.finish(run, file, seq, ref, null));
      return ChunkResult.offset(200, received, true);
    } catch (Exception e) {
      deleteTemp();
      Exception error = e;
      session.post(() -> cell.finish(run, file, seq, null, error));
      return ChunkResult.error(500, "upload could not be stored");
    }
  }

  private String contentType() {
    String guessed = java.net.URLConnection.guessContentTypeFromName(file.name());
    return guessed != null ? guessed : file.contentType();
  }

  /** Ends the upload with an error, dropping the partial file. */
  synchronized void fail(Throwable error) {
    if (closed) {
      return;
    }
    closed = true;
    session.engine.uploads.remove(token);
    deleteTemp();
    session.post(() -> cell.finish(run, file, seq, null, error));
  }

  /** Drops the partial file without an outcome, when the session itself is gone. */
  synchronized void discard() {
    closed = true;
    session.engine.uploads.remove(token);
    deleteTemp();
  }

  private void deleteTemp() {
    try {
      Files.deleteIfExists(temp);
    } catch (IOException e) {
      session.engine.log(System.Logger.Level.WARNING, "could not delete upload temp file " + temp, e);
    }
  }
}
