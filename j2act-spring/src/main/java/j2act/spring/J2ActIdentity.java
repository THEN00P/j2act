package j2act.spring;

import java.util.function.Function;

import j2act.AuthCtx;
import j2act.Exchange;

/**
 * Declare as a bean to give the mount an identity: turn the page-load request into an
 * AuthCtx on your own stack (Spring Security session, JWT cookie, ...). j2act ships no
 * auth adapters (ADR 0004).
 */
@FunctionalInterface
public interface J2ActIdentity extends Function<Exchange, AuthCtx> {
}
