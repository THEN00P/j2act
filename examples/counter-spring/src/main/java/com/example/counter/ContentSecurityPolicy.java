package com.example.counter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A strict nonce-based CSP: only scripts carrying the request's nonce run, and what they load
 * is trusted through 'strict-dynamic'. The nonce goes into a request attribute, where the
 * withCspNonce customizer in CounterApp reads it for the import map and runtime.js tags.
 */
@Component
public class ContentSecurityPolicy extends OncePerRequestFilter {

  public static final String NONCE_ATTRIBUTE = "cspNonce";

  private final SecureRandom random = new SecureRandom();

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
    throws ServletException, IOException {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    String nonce = Base64.getEncoder().encodeToString(bytes);
    request.setAttribute(NONCE_ATTRIBUTE, nonce);
    response.setHeader("Content-Security-Policy",
      "script-src 'nonce-" + nonce + "' 'strict-dynamic'; object-src 'none'; base-uri 'none'");
    chain.doFilter(request, response);
  }
}
