# Explicit unsafe and socket trust

Text escapes by default with no silent off-switch: raw HTML goes through one loud unsafeHtml() entry point (React dangerouslySetInnerHTML analogue), flagged by a build-time processor that runs under javac in Gradle/Maven and under JDTLS, so IDE and CI warn identically. Same loud treatment for javascript:/data: URLs in href/src, guard-bypass markers on routes, and unbounded in-memory upload buffering. Route-structure diagnostics ride the same processor pipeline under javac (Gradle/Maven) and JDTLS: a non-Layout class in layout() position is a hard error in both, mirroring Next.js build errors. Uncertain dynamic cases stay dev-overlay notes, never silent.

Sockets accept event invocations, so first paint mints a per-session CSRF token and every WS event and upload chunk is validated against it plus Origin/Host checks. Auth re-checks mean nothing if a foreign site can drive the victim's session.
