# j2act

Design lives in `CONTEXT.md` (glossary) and `docs/adr/` (decisions). Code style in `docs/STYLE.md`.

## Next-run orientation

- Build with the wrapper: `./mvnw install` (Maven 3.9.16, `--release 11`). JDKs live under `~/scoop/apps/temurin{11,17}-jdk` and `temurin-jdk` (26); check `java -version` first, the PATH once pointed at Java 8. Run on another JDK with `JAVA_HOME=~/scoop/apps/<jdk>/current ./mvnw ...`.
- Reactor: `j2act-core` (runtime, zero deps, client JS in `META-INF/resources/_j2act`), `j2act-html` (TagCreator stand-in for the j2html fork), `j2act-router`, `j2act-spring` (Boot 2.7 auto-config), `examples/counter-spring` (runnable demo + end-to-end test). `examples/team-dashboard` is a design sketch of future APIs, not in the reactor, and does not compile.
- Core tests drive sessions headlessly via `j2act-core/src/test/java/j2act/Harness.java`; handler ids rotate on every re-render, so take ids from the latest patch HTML, not the SSR page.
- Browser check: run the demo jar on port 8089, then `node tools/browser-check.mjs` (headless Chrome over CDP; the Claude in Chrome extension was not connected). Stop the demo before `./mvnw install`, or Windows keeps the jar locked and `repackage` fails.
- Git Bash heredocs containing apostrophes broke here; write files with the Write tool or a small python script instead.
