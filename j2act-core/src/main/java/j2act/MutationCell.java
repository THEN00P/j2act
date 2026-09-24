package j2act;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * Backs Mutation. Runs go to the executor; outcomes commit on the lane. The latest run
 * owns the visible state; callbacks and invalidations fire for every successful run.
 */
final class MutationCell extends Cell {

  static final class Snapshot {
    static final Snapshot IDLE = new Snapshot(MutationStatus.IDLE, null, null, null);

    final MutationStatus status;
    final Object data;
    final Throwable error;
    final Object variables;
    /** Upload progress in percent; 100 once done. */
    final int progress;

    Snapshot(MutationStatus status, Object data, Throwable error, Object variables) {
      this(status, data, error, variables, status == MutationStatus.SUCCESS ? 100 : 0);
    }

    Snapshot(MutationStatus status, Object data, Throwable error, Object variables, int progress) {
      this.status = status;
      this.data = data;
      this.error = error;
      this.variables = variables;
      this.progress = progress;
    }
  }

  Mutation<?, ?> config;
  private volatile Snapshot snapshot = Snapshot.IDLE;
  private long seq;

  MutationCell(Session session, String address, Mutation<?, ?> config) {
    super(session, address);
    this.config = config;
  }

  Snapshot read() {
    Snapshot s = snapshot;
    onRead(s);
    return s;
  }

  @Override Object peek() {
    return snapshot;
  }

  void mutate(Object variables) {
    if (session.lane.isCurrent()) {
      start(variables);
    } else {
      session.post(() -> start(variables));
    }
  }

  void reset() {
    Runnable reset = () -> {
      seq++;
      set(Snapshot.IDLE);
    };
    if (session.lane.isCurrent()) {
      reset.run();
    } else {
      session.post(reset);
    }
  }

  /** Lane-only. */
  private void start(Object variables) {
    if (disposed) {
      return;
    }
    long mySeq = ++seq;
    Mutation<?, ?> run = config;
    set(new Snapshot(MutationStatus.PENDING, null, null, variables));
    Runnable execution = () -> attempt(run, variables, mySeq, 0);
    if (run.key != null) {
      session.enqueueMutation(run.key, execution);
    } else {
      execution.run();
    }
  }

  private void attempt(Mutation<?, ?> run, Object variables, long mySeq, int attempt) {
    if (run instanceof Download) {
      offer((Download<?>) run, variables, mySeq);
      return;
    }
    if (run instanceof Upload) {
      offerUpload((Upload) run, (UploadFile) variables, mySeq);
      return;
    }
    try {
      session.engine.executor.execute(() -> {
        Object result = null;
        Throwable error = null;
        try {
          result = invoke(run, variables);
        } catch (Throwable t) {
          error = t;
        }
        if (error != null && attempt < run.retries) {
          long delay = run.backoff.toMillis() << attempt;
          session.engine.later(session, delay, () -> attempt(run, variables, mySeq, attempt + 1));
          return;
        }
        Object r = result;
        Throwable e = error;
        session.post(() -> finish(run, variables, mySeq, r, e));
      });
    } catch (RejectedExecutionException e) {
      session.post(() -> finish(run, variables, mySeq, null, e));
    }
  }

  /**
   * Lane-only. A download's body runs when the browser fetches it: mint a single-use
   * token, tell the client to fetch it, and fail the run if nobody does in time (ADR 0012).
   */
  private void offer(Download<?> run, Object variables, long mySeq) {
    J2Act engine = session.engine;
    String token = engine.newSecret(24);
    engine.downloads.put(token, new DownloadStream(session, this, run, variables, mySeq));
    session.send(Json.object("t", "dl", "u", engine.contextPath + J2Act.DOWNLOAD_PATH + token));
    engine.later(session, engine.downloadTtlMillis, () -> {
      if (engine.downloads.remove(token) != null) {
        finish(run, variables, mySeq, null, new IllegalStateException("download was not fetched in time"));
      }
    });
  }

  /**
   * Lane-only. Checks the browser's claims against the restrictions, then opens a temp
   * file and asks the client to send the bytes (ADR 0006). The byte limit is enforced
   * again on every chunk.
   */
  private void offerUpload(Upload run, UploadFile file, long mySeq) {
    J2Act engine = session.engine;
    Throwable refused = file == null ? new IllegalArgumentException("no file: pass the UploadFile from a file input's change event")
      : file.size() > run.maxFileSize ? new IllegalArgumentException(file.name() + " is larger than " + run.maxFileSize + " bytes")
      : !Uploads.accepts(run.accept, file) ? new IllegalArgumentException(file.name() + " is not an accepted type " + run.accept)
      : null;
    if (refused != null) {
      refuse(run, file, mySeq, refused);
      return;
    }
    java.nio.file.Path temp;
    try {
      temp = java.nio.file.Files.createTempFile(engine.uploadDir(), "up-", ".part");
    } catch (java.io.IOException e) {
      refuse(run, file, mySeq, e);
      return;
    }
    String token = engine.newSecret(24);
    engine.uploads.put(token, new UploadSink(token, session, this, run, file, mySeq, temp));
    session.send(Json.object("t", "up", "f", file.id, "u", engine.contextPath + J2Act.UPLOAD_PATH + token));
  }

  /**
   * Refused before the first chunk: the browser drops the file, and an UploadTarget.send
   * rejects with the reason, so no promise is left pending (ADR 0022).
   */
  private void refuse(Upload run, UploadFile file, long mySeq, Throwable reason) {
    if (file != null) {
      session.send(Json.object("t", "upx", "f", file.id, "e", String.valueOf(reason.getMessage())));
    }
    finish(run, file, mySeq, null, reason);
  }

  /** Lane-only. Progress of the latest run, while it is still pending. */
  void progress(long runSeq, int percent) {
    Snapshot s = snapshot;
    if (runSeq == seq && s.status == MutationStatus.PENDING && !disposed) {
      set(new Snapshot(MutationStatus.PENDING, null, null, s.variables, percent));
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object invoke(Mutation run, Object variables) throws Exception {
    return run.body.run(variables);
  }

  /** Lane-only. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  void finish(Mutation<?, ?> run, Object variables, long mySeq, Object result, Throwable error) {
    if (run.key != null) {
      session.mutationDone(run.key);
    }
    if (disposed) {
      return;
    }
    if (mySeq == seq) {
      set(error == null
        ? new Snapshot(MutationStatus.SUCCESS, result, null, variables)
        : new Snapshot(MutationStatus.ERROR, null, error, variables));
    }
    if (error == null) {
      for (List<Object> prefix : run.invalidates) {
        session.invalidate(prefix);
      }
      if (run.onSuccess != null) {
        ((java.util.function.Consumer) run.onSuccess).accept(result);
      }
    } else {
      if (error instanceof RouteException) {
        session.onRouteException((RouteException) error);
        return;
      }
      session.engine.log(System.Logger.Level.WARNING, "mutation failed at " + address, error);
      if (run.onError != null) {
        run.onError.accept(error);
      }
    }
  }

  private void set(Snapshot next) {
    snapshot = next;
    notifyObservers();
  }
}
