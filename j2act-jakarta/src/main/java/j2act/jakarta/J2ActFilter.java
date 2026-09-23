package j2act.jakarta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import j2act.Exchange;
import j2act.J2Act;
import j2act.PageResolver;
import j2act.ServeResult;

/** Serves GET requests for routed pages; everything else passes down the chain untouched. */
final class J2ActFilter implements Filter {

  private final J2Act j2Act;
  private final PageResolver resolver;

  J2ActFilter(J2Act j2Act, PageResolver resolver) {
    this.j2Act = j2Act;
    this.resolver = resolver;
  }

  @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
    throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!"GET".equals(request.getMethod()) || resolver.resolve(path) == null) {
      chain.doFilter(req, res);
      return;
    }
    String url = request.getQueryString() == null ? path : path + "?" + request.getQueryString();
    ServeResult result = j2Act.serve(url, exchange(request));
    HttpServletResponse response = (HttpServletResponse) res;
    response.setHeader("Cache-Control", "no-store");
    response.setStatus(result.status());
    if (result.location() != null) {
      response.setHeader("Location", result.location());
      return;
    }
    response.setContentType("text/html;charset=UTF-8");
    response.getOutputStream().write(result.html().getBytes(StandardCharsets.UTF_8));
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
}
