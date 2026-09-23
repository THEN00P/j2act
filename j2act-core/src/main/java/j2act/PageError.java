package j2act;

import java.util.List;

/** What an error() boundary gets: the first failure among its queries, and a way to retry them all. */
public final class PageError {

  private final List<QueryCell> failed;
  private final Throwable cause;

  PageError(List<QueryCell> failed, Throwable cause) {
    this.failed = failed;
    this.cause = cause;
  }

  public String message() {
    String message = cause.getMessage();
    return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
  }

  public Throwable cause() {
    return cause;
  }

  /** Refetches every failed query under the boundary; it shows loading() until they settle. */
  public void retry() {
    for (QueryCell query : failed) {
      query.refetch();
    }
  }
}
