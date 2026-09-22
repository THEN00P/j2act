# j2act

Design lives in `CONTEXT.md` (glossary) and `docs/adr/` (decisions). Code style in `docs/STYLE.md`. Machine-specific notes, if any, are in `CLAUDE.local.md` (gitignored).

## Next-run orientation

- Build with the wrapper: `./mvnw install` (Maven 3.9.16, `--release 11`). Java 11 is the baseline; any JDK from 11 up builds it. Run on another JDK with `JAVA_HOME=<jdk> ./mvnw ...`.
- Reactor: `j2act-codegen` (build-time generator, not shipped), `j2act-core` (runtime, zero deps, client JS in `META-INF/resources/_j2act`), `j2act-html` (generated typed tags + handwritten `TagHelpers`), `j2act-router`, `j2act-spring` (Boot 2.7 auto-config), `j2act-jakarta` (Jakarta EE 10 mount), `examples/counter-spring` (runnable demo + end-to-end test), `examples/counter-jakarta` (WAR for WildFly 27+). `examples/team-dashboard` is a design sketch of future APIs, not in the reactor, and does not compile.
- Never hand-edit `j2act-core/.../GlobalAttributes.java`, `j2act-html/.../TagCreator.java` or `j2act-html/.../tags/*`: change the generator or `j2act-codegen/src/main/resources/data/overrides.json`, then `./mvnw -pl j2act-codegen compile exec:java`. A test fails when checked-in output drifts. Refresh upstream data with `-Dexec.mainClass=j2act.codegen.UpdateData`.
- Core tests drive sessions headlessly via `j2act-core/src/test/java/j2act/Harness.java`; handler ids stay stable while their element keeps rendering at the same position and die when it stops rendering; after a structural change take ids from the latest patch HTML.
- If tests fail with "Unresolved compilation problems", an IDE language server (ECJ) wrote broken classes into `target/`; run `./mvnw clean install`.
- The Spring Boot BOM is imported only in `j2act-spring` and `examples/counter-spring`: it pins javax-era `jakarta.*` artifacts that would break the Jakarta EE 10 modules.
- Browser checks drive headless Chrome over CDP with no dependencies (`tools/cdp.mjs`, Node 22+; set `CHROME` if Chrome is not at the Windows default path). Spring: run the demo jar on port 8089, then `node tools/browser-check.mjs`. On Windows, stop the demo before `./mvnw install`, or the locked jar makes `repackage` fail.
- WildFly check: WildFly is not in the repo; unzip a 27+ release (34 is the last on Java 11), copy `examples/counter-jakarta/target/counter-jakarta.war` to `standalone/deployments/`, start `bin/standalone.sh`, then `node tools/wildfly-check.mjs`. Stop with `bin/jboss-cli.sh --connect --command=:shutdown`.
