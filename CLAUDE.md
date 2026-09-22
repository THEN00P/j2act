# j2act

Design lives in `CONTEXT.md` (glossary) and `docs/adr/` (decisions). Code style in `docs/STYLE.md`.

## Next-run orientation

- Build with the wrapper: `./mvnw install` (Maven 3.9.16, `--release 11`). JDKs live under `~/scoop/apps/temurin{11,17}-jdk` and `temurin-jdk` (26); check `java -version` first, the PATH once pointed at Java 8. Run on another JDK with `JAVA_HOME=~/scoop/apps/<jdk>/current ./mvnw ...`.
- Reactor: `j2act-codegen` (build-time generator, not shipped), `j2act-core` (runtime, zero deps, client JS in `META-INF/resources/_j2act`), `j2act-html` (generated typed tags + handwritten `TagHelpers`), `j2act-router`, `j2act-spring` (Boot 2.7 auto-config), `examples/counter-spring` (runnable demo + end-to-end test). `examples/team-dashboard` is a design sketch of future APIs, not in the reactor, and does not compile.
- Never hand-edit `j2act-core/.../GlobalAttributes.java`, `j2act-html/.../TagCreator.java` or `j2act-html/.../tags/*`: change the generator or `j2act-codegen/src/main/resources/data/overrides.json`, then `./mvnw -pl j2act-codegen compile exec:java`. A test fails when checked-in output drifts. Refresh upstream data with `-Dexec.mainClass=j2act.codegen.UpdateData`.
- Core tests drive sessions headlessly via `j2act-core/src/test/java/j2act/Harness.java`; handler ids stay stable while their element keeps rendering at the same position and die when it stops rendering; after a structural change take ids from the latest patch HTML.
- If tests fail with "Unresolved compilation problems", the IDE's language server (ECJ) wrote broken classes into `target/`; run `./mvnw clean install`.
- WildFly check: WildFly 29.0.1.Final (Java 11, the user's work stack) is not in the repo; unzip it anywhere, copy `examples/counter-jakarta/target/counter-jakarta.war` to `standalone/deployments/`, start `bin/standalone.sh` with `JAVA_HOME` on Temurin 11, then `node tools/wildfly-check.mjs`. Stop with `bin/jboss-cli.sh --connect --command=:shutdown`.
- The Spring Boot BOM is imported only in `j2act-spring` and `examples/counter-spring`: it pins javax-era `jakarta.*` artifacts that would break the Jakarta EE 10 modules.
- Browser check: run the demo jar on port 8089, then `node tools/browser-check.mjs` (headless Chrome over CDP; the Claude in Chrome extension was not connected). Stop the demo before `./mvnw install`, or Windows keeps the jar locked and `repackage` fails.
- Git Bash heredocs containing apostrophes broke here; write files with the Write tool or a small python script instead.
