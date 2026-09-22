package com.example.team.auth;

import java.util.Optional;

import j2act.AuthCtx;
import j2act.Exchange;

/**
 * Host-owned identity seam. Five lines on the app's own stack — J2ACT ships no
 * Spring/Jakarta/OIDC/SAML adapters. Invoked lazily, only when a guard or
 * render actually reads AuthCtx.
 */
public final class AppIdentity {

  private AppIdentity() {}

  public static AuthCtx resolve(Exchange ex) {
    Optional<String> session = ex.cookie("SESSION");
    if (session.isEmpty()) {
      return AuthCtx.anonymous();
    }
    return MySessions.lookup(session.get())
      .map(u -> AuthCtx.of(u.name(), u.roles()))
      .orElseGet(AuthCtx::anonymous);
  }
}
