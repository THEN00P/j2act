package j2act;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A claimed download, for the adapter to serve: set the headers, then writeTo() the
 * response stream. The outcome is committed to the Mutation on the session's lane.
 */
public final class DownloadStream {

  final Session session;
  private final MutationCell cell;
  private final Download<?> run;
  private final Object variables;
  private final long seq;
  private final AtomicBoolean finished = new AtomicBoolean();

  DownloadStream(Session session, MutationCell cell, Download<?> run, Object variables, long seq) {
    this.session = session;
    this.cell = cell;
    this.run = run;
    this.variables = variables;
    this.seq = seq;
  }

  public String contentType() {
    return run.contentType;
  }

  public String fileName() {
    return run.fileName;
  }

  /** An attachment disposition with an ASCII fallback and the RFC 5987 UTF-8 name. */
  public String contentDisposition() {
    String name = run.fileName;
    StringBuilder ascii = new StringBuilder();
    for (char c : name.toCharArray()) {
      ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
    }
    StringBuilder encoded = new StringBuilder();
    for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
          .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
  }

  /** Runs the writer on the calling thread. The Mutation succeeds after the last byte, or fails with the writer. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void writeTo(OutputStream out) throws IOException {
    try {
      ((Download.Writer) run.writer).write(variables, out);
      out.flush();
    } catch (Throwable t) {
      fail(t);
      if (t instanceof IOException) {
        throw (IOException) t;
      }
      throw new IOException("download writer failed", t);
    }
    complete(null);
  }

  void fail(Throwable error) {
    complete(error);
  }

  private void complete(Throwable error) {
    if (finished.compareAndSet(false, true)) {
      session.post(() -> cell.finish(run, variables, seq, null, error));
    }
  }
}
