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

import j2act.Asset;
import j2act.ChunkResult;
import j2act.DownloadStream;
import j2act.Exchange;
import j2act.J2Act;
import j2act.PageResolver;
import j2act.ServeResult;

/**
 * Serves GET requests for routed pages, download tokens and client modules, and upload chunk POSTs;
 * everything else passes down the chain untouched.
 */
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
    if ("POST".equals(request.getMethod()) && path.startsWith(J2Act.UPLOAD_PATH)) {
      chunk(j2Act, path.substring(J2Act.UPLOAD_PATH.length()), request, (HttpServletResponse) res);
      return;
    }
    if ("GET".equals(request.getMethod()) && path.startsWith(J2Act.DOWNLOAD_PATH)) {
      download(j2Act, path.substring(J2Act.DOWNLOAD_PATH.length()), request, (HttpServletResponse) res);
      return;
    }
    if ("GET".equals(request.getMethod()) && path.startsWith(J2Act.PACKAGE_PATH)) {
      Asset file = j2Act.packageFile(path.substring(J2Act.PACKAGE_PATH.length()));
      HttpServletResponse response = (HttpServletResponse) res;
      if (file == null) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
      }
      // An npm package file from an mvnpm jar; CommonJS arrives as an ES module (ADR 0022).
      response.setContentType(file.contentType());
      response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.getOutputStream().write(file.bytes());
      return;
    }
    if ("GET".equals(request.getMethod()) && path.startsWith(J2Act.MODULE_PATH)) {
      module(j2Act, path.substring(J2Act.MODULE_PATH.length()), (HttpServletResponse) res);
      return;
    }
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

  /** Appends one upload chunk from the request body (ADR 0006); the answer tells the client where to resume. */
  static void chunk(J2Act j2Act, String token, HttpServletRequest request, HttpServletResponse response)
    throws IOException {
    long offset;
    try {
      offset = Long.parseLong(request.getParameter("o"));
    } catch (NumberFormatException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    ChunkResult result = j2Act.acceptChunk(token, offset, request.getInputStream(), exchange(request));
    response.setHeader("Cache-Control", "no-store");
    response.setStatus(result.status());
    response.setContentType("application/json");
    response.getOutputStream().write(result.json().getBytes(StandardCharsets.UTF_8));
  }

  /** Streams a claimed download straight into the response (ADR 0012); 404 for a bad token. */
  static void download(J2Act j2Act, String token, HttpServletRequest request, HttpServletResponse response)
    throws IOException {
    DownloadStream download = j2Act.claimDownload(token, exchange(request));
    response.setHeader("Cache-Control", "no-store");
    if (download == null) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    response.setContentType(download.contentType());
    response.setHeader("Content-Disposition", download.contentDisposition());
    response.setHeader("X-Content-Type-Options", "nosniff");
    download.writeTo(response.getOutputStream());
  }

  /** A client module (ADR 0022); its URL carries a content hash, so it is cached for good. */
  static void module(J2Act j2Act, String path, HttpServletResponse response) throws IOException {
    Asset module = j2Act.module(path);
    if (module == null) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    response.setContentType(module.contentType());
    response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.getOutputStream().write(module.bytes());
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
