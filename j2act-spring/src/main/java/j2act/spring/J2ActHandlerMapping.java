package j2act.spring;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import j2act.Exchange;
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

  /** The request as the identity function sees it; replayed before each event (ADR 0004). */
  static Exchange exchange(HttpServletRequest request) {
    Map<String, String> cookies = new LinkedHashMap<>();
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        cookies.putIfAbsent(cookie.getName(), cookie.getValue());
      }
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      headers.put(name, Collections.list(request.getHeaders(name)));
    }
    return Exchange.of(cookies, headers);
  }

  @Override protected Object getHandlerInternal(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod())) {
      return null;
    }
    String path = initLookupPath(request);
    if (resolver.resolve(path) == null) {
      return null;
    }
    String url = request.getQueryString() == null ? path : path + "?" + request.getQueryString();
    return (HttpRequestHandler) (req, res) -> {
      ServeResult result = j2Act.serve(url, exchange(req));
      res.setHeader("Cache-Control", "no-store");
      if (result.location() != null) {
        res.setStatus(result.status());
        res.setHeader("Location", result.location());
        return;
      }
      res.setStatus(result.status());
      res.setContentType("text/html;charset=UTF-8");
      res.getOutputStream().write(result.html().getBytes(StandardCharsets.UTF_8));
    };
  }
}
