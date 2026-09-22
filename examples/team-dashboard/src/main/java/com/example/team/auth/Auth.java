package com.example.team.auth;

import j2act.Allow;
import j2act.AuthCtx;
import j2act.Middleware;
import j2act.Redirect;
import j2act.Verdict;

/** Shared subtree guard. Unit-testable in isolation; framework instantiates it. */
public class Auth implements Middleware {

  private final String role;

  public Auth() {
    this("admin");
  }

  public Auth(String role) {
    this.role = role;
  }

  @Override public Verdict check(AuthCtx ctx) {
    return ctx.hasRole(role) ? Allow : Redirect.to("/login");
  }
}
