package j2act;

/**
 * A route guard (ADR 0005 "Guard"): runs on every full load and soft navigation into
 * its scope, and again whenever the tab's identity changes.
 */
@FunctionalInterface
public interface Middleware {

  Verdict check(AuthCtx auth);
}
