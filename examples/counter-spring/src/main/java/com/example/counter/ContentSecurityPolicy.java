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
 * Allows the page's same-origin browser distributions and a nonce on runtime.js. The
 * withCspNonce customizer reads the nonce from the page-load request's attribute.
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
      "script-src 'self' 'nonce-" + nonce + "'; object-src 'none'; base-uri 'none'");
    chain.doFilter(request, response);
  }
}
