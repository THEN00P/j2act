# Spike: Vite, Tailwind and the IDE build loop

Branch `spike/frontend-build`. Throwaway prototypes that answer how TypeScript, npm packages, CSS modules and Tailwind reach a j2act app built with Gradle or Maven, run from an IDE or a server, with one launch and no second dev server. The results feed the ADRs; the code is not the final shape.

## What was built

- `spike/vite-plugin`: `@j2act/vite`, a Vite plugin.
  - Every `*.client.{ts,js,...}` under `src/main/java` is an entry, named after its class's resource path (`com/example/SalesChart.client`), plus page entries from `j2act({ input: [...] })`.
  - The output goes onto the classpath with Vite's standard manifest: `build/j2act` for Gradle, `target/classes` for Maven, under `META-INF/j2act/vite/`.
  - Files are write-once, since their names carry a content hash. Vite writes into a staging folder per process; new files are moved in, existing ones are left alone, and the manifest goes live last in one rename.
  - `dev.js` runs `vite build --watch` and exits when its stdin closes. Its stdin is a pipe from the app, so it ends with the app's JVM, however that JVM ends.
- `spike/gradle-plugin`: `id("dev.j2act")`.
  - Adds the processor and the resources rule for plain `.client.js`.
  - With a package.json, it applies node-gradle (Node download, `npmInstall`/`pnpmInstall`) and registers `j2actBundle`, which runs the package's `build` script (tsc, then Vite). Its output joins the main source set, so `war`, `bootJar`, `bootRun` and tests see it.
  - Leaves the dev token out of archives. This needs the root spec: `War` and `BootJar` add `WEB-INF/classes` and `BOOT-INF/classes` beside the main spec, so a task-level `exclude` never reaches them.
  - Buildship: `autoBuildTasks(j2actBundle)` and `synchronizationTasks(npmInstall)`.
- `spike/maven-plugin`: `j2act-maven-plugin:bundle`.
  - Runs the package's `build` script in `process-classes`.
  - Its m2e metadata says `runOnIncremental`. In an incremental build it runs only when m2e's `BuildContext` reports a changed frontend file, then refreshes its output.
- Runtime (`j2act-core`):
  - `Modules` reads the Vite manifest and serves a chunk, its imports and every stylesheet they carry, following Vite's backend-integration rule.
  - `Vite.vite("src/main/frontend/app.css")` renders a page entry's tags, like Laravel's `@vite`.
  - `DevMode` is on when three things hold:
    - the class output carries `META-INF/j2act/dev.json`;
    - the project folder named in it exists on this machine;
    - the token is not inside a jar.
  - It is off under test runners and with `-Dj2act.dev=false`. In dev mode it reads the Vite output from the project folder, polls the manifest, starts `dev.js`, and tells pages what moved.
  - `runtime.js` swaps moved stylesheets in place, and mounts a client again when its module URL changed, with its current props.
- Processor (`j2act-processor`):
  - Writes the dev token.
  - Also writes the client types to `.j2act/types` in the project (see findings).
  - Is registered with Gradle as an `isolating` incremental processor.
- Samples: `spike/gradle-wildfly` (WAR), `spike/gradle-spring` and `spike/maven-wildfly`. Each has one TS client module with Chart.js and a CSS module, and a Tailwind page entry styling classes written in Java.
- Checks:
  - `spike/check.mjs`: page, Tailwind, Chart.js, CSS module, clicks and props; with `--reimport`, a live edit.
  - `spike/stress.mjs`: two writers.
  - `spike/jdtls-probe.mjs`: VS Code's Java server, headless.

## Results

Scenario numbers are the spike plan's.

| Row | 1 fresh clone | 2 live edit | 3 edit while stopped | 4 types on save | 5 Tailwind in Java | 7/8 package, deploy | 9 tests | 10 two writers | 11 no Node |
|---|---|---|---|---|---|---|---|---|---|
| Gradle CLI, WildFly 29 / Java 11 | pass | n/a | pass | n/a | pass | pass, 6/6 | n/a | n/a | pass (plugin downloads Node) |
| Gradle `bootRun` (Spring) | pass | pass, 9/9 | pass | n/a | pass | `bootJar` pass, 6/6 | pass | pass | pass |
| VS Code, Gradle (JDT LS + Buildship, headless) | pass: import runs `npmInstall` | pass, 9/9 on a VS Code-style launch | covered by 2 | pass: `.j2act/types` | pass | not tested | n/a | covered by 10 | pass |
| Maven CLI, WildFly 29 / Java 11 | pass | n/a | pass | n/a | pass | pass, 6/6 | n/a | n/a | pass (frontend-maven-plugin) |
| VS Code, Maven (JDT LS + m2e, headless) | pass | m2e rebuilds on a `.ts` edit | pass | pass: `.j2act/types` | not tested | not tested | n/a | n/a | pass |
| Eclipse IDE, Gradle and Maven | your run, see the checklist | | | | | | | | |
| IntelliJ | not run (no license for Jakarta EE); mirrors Vaadin, see below | | | | | | | | |

**Scenario 10:** 60 s of edits every 0.7 s, with the app's watcher and a looping one-shot `vite build` writing the same folder. Two readers loaded 57,867 pages and 173,601 files; every file arrived whole. There were 84 live re-imports and 0 build errors.

**Hard kill:** the IDE's terminate button kills the JVM without shutdown hooks. With the stdin pipe, no `node` process is left behind; before that fix, one was.

The regular checks still pass on this branch: full build, Spring 75/75, WildFly 29 30/30, ts-npm 11/11, ts-pnpm 11/11.

## Findings

1. **Vite works as the reference.**
   - Standard Vite plus `@tailwindcss/vite`, with no j2act-specific CSS handling.
   - Tailwind v4 finds class names in `.java` files by itself, and a Java edit triggers a CSS rebuild in watch mode.
   - It relies on `.gitignore`: without `build/` ignored, it scanned the build output and the CSS grew.
2. **Types need a single location.**
   - `SOURCE_OUTPUT` differs by tool: Maven and m2e use `target/generated-sources/annotations`; Gradle uses `build/generated/sources/annotationProcessor/java/main`; Buildship in VS Code uses `bin/generated-sources/annotations`.
   - `.j2act/types` at the project root is the same everywhere, as with SvelteKit's `.svelte-kit/types`, Nuxt's `.nuxt` and React Router's `.react-router/types`. `tsconfig.json` names it once: `"rootDirs": ["src/main/java", ".j2act/types"]`.
   - Cost: these files are written outside the Filer, so Gradle never removes stale ones.
3. **The IDE classpath does not reliably hold the Vite output.** Buildship left `build/j2act` off VS Code's classpath, and a WTP exploded deployment is a copy. So dev mode reads the output from the project folder, as Vaadin's dev mode does. Production and exports read it from the classpath.
4. **Eclipse builds differ by build tool.**
   - m2e runs a mojo with `runOnIncremental` on every relevant save, so Maven projects in Eclipse and VS Code keep `target/classes` fresh, and an Eclipse export packages it.
   - Buildship in VS Code runs `synchronizationTasks` but not `autoBuildTasks`. The Eclipse IDE itself is unverified.
5. **Two writers are safe by construction:** hashed files are written once and the manifest is renamed into place. The first prototype had a real race: a writer's cleanup walked into the other writer's staging folder. That is fixed.
6. **Gradle recompiled everything on every change** ("Full recompilation is required because j2act.processor.J2ActProcessor is not incremental").
   - Registering the processor as `isolating` fixes it: "Incremental compilation of 2 classes".
   - Gradle's rules say incremental processors must not use `Trees`. Lombok does anyway, through the same wrapper unwrapping our `JavacSource` already had, and our checks still warn under Gradle.
   - The dev token needed an originating element; it takes the first compiled type.
7. **A dev process started by the app must die with it.** With the stdin-EOF runner, it does.
8. **Smaller points:**
   - `BootJar` copies `META-INF/**` of the classes folders to the jar root, so the Vite output is in the boot jar twice. A classpath root outside `META-INF` avoids that.
   - The last node-gradle release is from September 2024. It works on Gradle 9.8, but it is a maintenance risk; Vaadin downloads Node itself.
   - Spring Boot 3.5's Gradle plugin prints a Gradle 10 deprecation (`Configuration.setVisible`). It is not ours.
   - Source maps currently go into production archives.

## Open decisions for the ADRs

- **TypeScript errors and IDE runs:**
  - `j2actBundle` runs the package's `build` script, so a TS type error fails `classes`. That also blocks an IntelliJ Run of a Gradle project, since IntelliJ delegates to Gradle.
  - The alternative is Vite-only in `classes` and `tsc` in `check`. Quarkus Quinoa runs the package's build script.
- **Source maps in production:** on, `hidden`, or off.
- **Where on the classpath the output lives:** `META-INF/j2act/vite` duplicates in `BootJar`.
- **Node for Gradle:** keep node-gradle, or download Node ourselves as Vaadin does.
- **Maven setup:**
  - The spike pom still lists frontend-maven-plugin, the war `packagingExcludes` for the token, and the `src/main/java` resources.
  - A plugin with `<extensions>true</extensions>` could apply all of it itself, matching what the Gradle plugin does.
- **The dev token in Maven archives** (`packagingExcludes`) versus relying only on the folder-exists check.

## IntelliJ (not run here)

IntelliJ Community has no Jakarta EE or WildFly run configurations, so there is nothing to mirror for WildFly. For Spring, IntelliJ builds Gradle projects through Gradle by default, so `j2actBundle` runs on every Run. For Maven projects it uses its own builder, which runs annotation processors but no Maven plugins. The processor still writes the dev token there, so dev mode starts the watcher, just as Vaadin handles IntelliJ with its dev server.

## Eclipse checklist (for you to run)

Setup, once:
1. On this branch, run `./mvnw install` at the repo root.
2. Run `../../mvnw install` in `spike/maven-plugin`.
3. The Gradle plugin needs no install; it comes through `includeBuild`.

For each of `spike/gradle-wildfly` (Import > Existing Gradle Project) and `spike/maven-wildfly` (Import > Existing Maven Projects), write down what happens:

- **E1 Import.** Expected:
  - `node_modules` appears; for Gradle, Node lands in `.gradle/nodejs`.
  - `.j2act/types/com/example/spike/components/SalesChart.types.d.ts` exists.
  - The Problems view has no errors.
- **E2 Processor.** Add a component in `each(...)` without `withKey` to any page, for example `div(each(List.of("a"), n -> salesChart()))`.
  - Is there a j2act warning in Problems?
  - For Gradle this is the open question, since Buildship does not configure annotation processing.
- **E3 Save a `.ts` edit** (change `const BUILD = "one"`). Does the build output update without a Gradle or Maven run?
  - Gradle: `build/j2act/META-INF/j2act/vite/.vite/manifest.json`
  - Maven: `target/classes/META-INF/j2act/vite/.vite/manifest.json`
- **E4 WildFly 29 from Eclipse.** Add the server, deploy the project, open `http://localhost:8080/gradle-wildfly/` (or `/maven-wildfly/`).
  - If the Gradle project cannot be added to the server, add `eclipse-wtp` to its `plugins { }` and refresh the Gradle project. Note it: the j2act plugin would then apply it.
  - Expected in the server log: `j2act dev: vite build --watch in ...`.
  - Change the `BUILD` stamp and save: the chart mounts again without a page reload, and the count stays.
  - Add a Tailwind class in `HomePage.java`, save, click `+1`: the style applies.
- **E5 Project > Clean:** the page still works.
- **E6 Terminate** the server with the red button. Is any `node.exe` running `dev.js` left in Task Manager?
- **E7 Export > WAR file.** Deploy it to a WildFly started outside Eclipse.
  - The page works, and the log has no `j2act dev` lines.
  - The chart's `data-build` shows your last saved stamp.
  - The WAR has no `META-INF/j2act/dev.json`; it matters less than the stamp.
