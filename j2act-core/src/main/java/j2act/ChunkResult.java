package j2act;

/** The answer to one upload chunk: an HTTP status and a small JSON body for the client runtime. */
public final class ChunkResult {

  private final int status;
  private final String json;

  ChunkResult(int status, String json) {
    this.status = status;
    this.json = json;
  }

  static ChunkResult offset(int status, long offset, boolean done) {
    return new ChunkResult(status, "{\"o\":" + offset + (done ? ",\"done\":1" : "") + "}");
  }

  static ChunkResult error(int status, String message) {
    return new ChunkResult(status, Json.object("error", message));
  }

  public int status() {
    return status;
  }

  /** application/json */
  public String json() {
    return json;
  }
}
