# Event errors, logging, notFound and redirect

An exception in an event handler logs and nothing else visible: already-applied State writes stay (State is not transactional), the element's pending state reverts (ADR 0013), and the server log shows the stack trace. No boundary re-render: ADR 0007's error() is for failed initial loads, not handler bugs.

Logging goes through java.lang.System.Logger (JEP 264, Java 9+): zero dependencies and no adapter config. The default backend is JUL, which both hosts already route: WildFly sends JUL into JBoss LogManager, and Spring Boot's starter installs jul-to-slf4j. Anyone else adds their backend's bridge (log4j-jpl, slf4j-jdk-platform-logging). We avoid SLF4J and JBoss Logging as hard deps: either one pulls a facade version into hosts that pin their own. Hibernate's choice of JBoss Logging solves the same problem with a dependency we don't need on Java 11.

notFound() and redirect(path) are static in Routes, throwable from render, a query loader or a handler. notFound() renders the nearest fallback() page. On a full-page serve it sends a real 404, and redirect() sends a 303. Over the socket both become soft navigations (ADR 0011). Status codes matter for crawlers and are the only new server behaviour. Everything else rides existing route nodes.
