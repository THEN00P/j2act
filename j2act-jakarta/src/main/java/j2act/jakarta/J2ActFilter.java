package j2act.jakarta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
    ServeResult result = j2Act.serve(path);
    HttpServletResponse response = (HttpServletResponse) res;
    response.setStatus(result.status());
    response.setContentType("text/html;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.getOutputStream().write(result.html().getBytes(StandardCharsets.UTF_8));
  }
}
