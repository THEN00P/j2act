# Java 11 runtime: executor seam and hotswap dev loop

Java 11 is the baseline because a large share of enterprise shops, including new apps, are stuck there. Java 8 is out because Oracle dropped it. Without virtual threads, blocking user code (JDBC, REST) must never run on socket I/O threads: every event, mutation and effect runs on a session's serial lane, and query loaders run off-lane with validated commits (ADR 0020), all over a bounded pool sized for blocking work.

That pool is a seam, not ours. The Jakarta adapter defaults to the container's ManagedExecutorService so CDI, JNDI, @PersistenceContext and JTA context propagate into handlers, since plain threads on WildFly lose them. The Spring adapter defaults to the app's TaskExecutor. withExecutor(...) overrides either one, which is also where Java 21 virtual threads drop in later with no API change.

No framework dev tooling. The dev loop is the host's: run WildFly or Spring Boot under the IDE debugger and JVM HotSwap (Eclipse, IntelliJ, VS Code) swaps method bodies live. Next event or navigation re-renders with the new code. Structural changes need a redeploy, which kills sessions, and the client re-mounts per ADR 0010. Inserting a component shifts tree slots and may re-mount later siblings (ADR 0019). That's acceptable. React-style HMR waits for the devtools work.
