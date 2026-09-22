package j2act.spring;

import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import j2act.J2Act;
import j2act.PageResolver;
import j2act.ServeResult;

/**
 * Serves GET requests for routed pages. Ordered after @Controller mappings, so the
 * host's own endpoints win, and before static resources.
 */
public class J2ActHandlerMapping extends AbstractHandlerMapping {

  private final J2Act j2Act;
  private final PageResolver resolver;

  public J2ActHandlerMapping(J2Act j2Act, PageResolver resolver) {
    this.j2Act = j2Act;
    this.resolver = resolver;
    setOrder(1);
  }

  @Override protected Object getHandlerInternal(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod())) {
      return null;
    }
    String path = initLookupPath(request);
    if (resolver.resolve(path) == null) {
      return null;
    }
    return (HttpRequestHandler) (req, res) -> {
      ServeResult result = j2Act.serve(path);
      res.setStatus(result.status());
      res.setContentType("text/html;charset=UTF-8");
      res.setHeader("Cache-Control", "no-store");
      res.getOutputStream().write(result.html().getBytes(StandardCharsets.UTF_8));
    };
  }
}
