package j2act;

/** Maps a request path to a page. The router module implements it. */
@FunctionalInterface
public interface PageResolver {

  /** The matching page, or null for no route. */
  PageMatch resolve(String path);
}
