# Gradle and Maven plugins, and the IDE builds they configure

Gradle is a first-class build, as Maven is. The Gradle plugin is built with Gradle 8.14 and Java 11 bytecode, so builds that still run Gradle on Java 11 can use it, and its TestKit tests run on Gradle 8 and 9; the first production user builds with Gradle and deploys to WildFly. Both builds get a j2act plugin, id("dev.j2act") and dev.j2act:j2act-maven-plugin, with Vaadin's and Quarkus' plugins as precedent. The plugins only supply defaults. Everything the user sets in node { }, in vite.config, in the pom or in the build script wins.

    plugins {
      war
      id("dev.j2act")
    }

The Gradle plugin:
- puts j2act-processor on the annotationProcessor path;
- adds plain .client.js files, and what they import, from src/main/java to the resources. It uses processResources { from("src/main/java") { include(...) } }, because resources.srcDir with an include filters every resource folder.
- With a package.json, it applies node-gradle with conventions only: Node downloaded, version 24, and node-gradle's npm install rather than npm ci. The IDE runs the install on every refresh or full build, and npm ci deletes node_modules under the running dev watcher; on Windows the files the watcher holds open stop it halfway, which left a broken install in a spike run. The package manager comes from package.json's packageManager field, else the lockfile, else npm. node-gradle's last release is from 2024; we keep it for its configuration and fork it if it is ever abandoned.
- registers j2actBundle (vite build), whose output folder joins the main source set, so run, test, war and bootJar see it. With Tailwind among the dependencies, the Java sources are inputs too.
- registers j2actTypecheck (tsc --noEmit) under check.

The Maven plugin has two goals:
- bundle, in process-classes, runs Vite through Node directly, with no npm or shell in between;
- typecheck runs in the test phase.

With <extensions>true</extensions>, as the Kotlin Maven plugin does it, the Maven plugin applies the rest as defaults after Maven reads the pom:

    <plugin>
      <groupId>dev.j2act</groupId>
      <artifactId>j2act-maven-plugin</artifactId>
      <version>...</version>
      <extensions>true</extensions>
    </plugin>

- the bundle and typecheck executions, unless the pom lists executions of its own;
- j2act-processor on maven-compiler-plugin's annotationProcessorPaths, after any the pom lists;
- src/main/java as one more resource folder for .js, .mjs, .cjs, .css and .json, unless the pom lists that folder;
- m2e.apt.activation=jdt_apt, unless set;
- with a package.json, frontend-maven-plugin installing Node 24 and the package manager (chosen as the Gradle plugin chooses it) and running its install, unless the pom declares frontend-maven-plugin. nodeVersion, pnpmVersion and yarnVersion stay that plugin's own properties;
- for a WAR, exploded-hotswap's Maven plugin, so mvn exploded-hotswap:exploded resolves.

m2e runs Maven lifecycle participants when it reads a project (m2e 2.x, ProjectRegistryManager), so Eclipse and VS Code see the same processor path, property and executions. Checked with VS Code's Java server: the processor ran, and the bundle ran on import and after an edit.

The trade-off is the processor path. A pom that lists no annotationProcessorPaths lets javac find processors on the classpath, and giving it a path turns that search off, so a Lombok taken from the classpath stops running. JDK 23 already turned that search off by default, and Lombok and MapStruct document the processor path. Such a pom lists Lombok there.

The TypeScript copy filter for Eclipse stays out: m2e has no pom setting for it. The copies in target/classes are harmless under Maven, since tsconfig.json includes only src/main/java and the runtime serves only the files Vite's manifest lists.

Maven resolves build extensions before it builds anything, so this repository's Maven Vite examples cannot sit in the reactor that builds the plugin. They are their own build, examples/pom.xml, after ./mvnw install, as the Gradle examples are.

Type errors fail gradle build, mvn package and mvn verify, the way a failing test does, but never compiling or starting the app. An IntelliJ Run of a Gradle project goes through Gradle, so a type check inside the bundle task would have blocked it.

Every task the Gradle plugin adds or configures works with Gradle's configuration cache, checked on Gradle 8.14 and 9.8. The check found that explodedWar chose between the editor's classes and javac's when the cache entry was stored. A reused entry then kept that first choice, and Gradle 8.14 could not load it at all. The choice is now made when the task runs.

The processor is registered with Gradle as an isolating incremental processor. Before, every Java change recompiled the whole project. Gradle's rules forbid javac's Trees API to incremental processors, and the build-time checks need it. Like Lombok, the processor unwraps Gradle's processing environment. That is safe here because the checks read only the type being compiled. The dev token names the first compiled type as its origin, as isolating processors must.

The IDE builds have to produce what the command-line build does, or an IDE-exported WAR drifts from what was run. The spike's Eclipse rounds (docs/spikes/frontend-build.md) settled this:

Eclipse with Maven (m2e):
- The bundle goal declares runOnIncremental in its m2e metadata. It asks m2e's BuildContext which files changed and runs when a frontend file changed or no build output exists. It retries once, since a Clean can empty the folder under it, puts Vite's own last lines into the error Eclipse shows, and refreshes its output.
- m2e runs annotation processors only when the pom sets m2e.apt.activation=jdt_apt; its default is disabled.
- frontend-maven-plugin's npm goal was not enough on its own: in incremental builds it runs only when package.json changed.

Eclipse with Gradle (Buildship):
- Buildship sets up no annotation processing. The plugin's j2actEclipseApt task writes it on every Gradle refresh (a Buildship synchronization task): the processor path in .factorypath, APT on with a project-relative generated source folder under bin/, parameter names stored, and TypeScript left out of the copied resources (Eclipse's checker read a stale copy in bin/main, and WTP published it).
- goomph's apt plugin was dropped: it writes the generated source folder as an absolute path, which Eclipse on Windows reads as project-relative.
- A WAR project gets eclipse-wtp, with a Jakarta EE 10 web facet in place of Gradle's Servlet 2.4 default. bin/main and build/j2act are mapped whole into WEB-INF/classes. WTP publishes a Gradle project's source folders file by file, and left the processor's resources behind. bin/default, where Eclipse writes those resources, is mapped as well.
- The mappings are added when the component file is merged, since Gradle's resource() drops folders that do not exist yet, as on a fresh import.
- autoBuildTasks runs j2actBundle on every Eclipse build, and synchronizationTasks installs Node and node_modules on import.
- The Java level comes from the toolchain, which Buildship reads; it ignores options.release.

VS Code:
- Its Java server (JDT LS) sets up annotation processing for Gradle itself and runs synchronization tasks, but not autoBuildTasks.
- It exports no WARs. While the app runs, dev mode keeps the build current.

IntelliJ:
- It runs Gradle projects through Gradle by default, so the plugin's tasks run.
- For Maven it uses its own builder, which runs annotation processors but no Maven plugins, so dev mode's watcher does the frontend build, as with Vaadin.
- It was not tested (no Jakarta EE license), and its Jakarta EE server integration is out of scope.

A WildFly Gradle plugin is out of scope: every IDE deploys to WildFly on its own. What the IDE deploys decides dev mode and HotSwap, so the j2act plugin applies exploded-hotswap (io.github.then00p.exploded-hotswap, a separate plugin since nothing in it is j2act's). Its explodedWar unpacks the WAR into build/exploded/<name>.war on every build; VS Code's Runtime Server Protocol tooling and JBoss Tools deploy that folder, and a folder, unlike a WAR file, runs in dev mode.

The folder carries the classes the editor compiled whenever every source has a class in bin/main at least as new as itself; otherwise javac's, with a log line saying so. Resources always come from Gradle's build. This is what makes HotSwap work outside Eclipse's WTP. Editors built on the Eclipse Java language server (VS Code, Neovim, Zed) push classes compiled by the Eclipse compiler, and the JVM refuses them over javac's with "delete method not implemented", because the two name lambda methods differently. Checked through JDWP on WildFly 29: the javac build of an edit was refused, the Eclipse compiler's was accepted, and the page showed it on its next render without a reload.

Standard HotSwap swaps method bodies only, and a component's lambdas are methods: an edit that adds a handler, a computed or a helper method is refused. JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition takes those edits too, so we recommend it for running the server while developing, at the cost of a different JVM in development than in production. tools/hotswap-check.mjs checked it on WildFly 29: an edit adding a button with a new lambda and a helper method, then its removal, swapped in both directions under JetBrains Runtime 11 (its DCEVM build, whose last release is 11.0.16) and 21, with javac's classes as with the Eclipse compiler's, and the page kept its state. Temurin 11 refused the same edit ("add method not implemented"). JetBrains Runtime 17, 21 and 25 have the flag in their regular builds. Dev mode logs a hint while a debugger is attached: on another JVM, a download link for JetBrains Runtime of the running Java version; on JetBrains Runtime, that the flag is off.

The JVM does not tell the app about a swap, and a page left alone would keep the old markup and the old handlers. Those are the last render's lambdas, called by method name, so after a swap that removes or renumbers lambdas the first click failed with NoSuchMethodError, or would run another lambda's body when a name was reused. So while a debugger is attached, dev mode watches the class files: the folder the app loads from, and the editor's output (bin/main, target/classes). A change there means the IDE has built and is swapping. Every session re-renders with the new code about a quarter of a second later, and once more after a second and a half, since the class file can land before or after the debugger's swap. State stays, as it lives in the session. Two cases start over instead:
- A component whose primitives or fields no longer line up gets fresh state (ADR 0019).
- A page or layout whose fields changed is mounted again. Its object is held across renders, and the JVM leaves new fields of existing objects unset.

A new class in the editor's output holds the re-render back until the deployment has it (explodedWar -t, or mvn exploded-hotswap:exploded). Code that runs into a class its class loader cannot find fails, and the JVM keeps that failure for that reference until the class is swapped again. tools/hotswap-check.mjs checks all of it on WildFly 29 with JetBrains Runtime 11: an edit adding a button, a lambda and a helper shows without a click and keeps the count, an edit adding a field mounts the page again, and the way back works too, with javac's classes and with the Eclipse compiler's.

Maven has the same problem without the separate folder: the editor and mvn share target/classes, and mvn compile, test and package recompile the whole module with javac whenever a file was added or removed, after a clean or a pull. So HotSwap there would fail seemingly at random. exploded-hotswap's Maven goal, mvn exploded-hotswap:exploded, runs instead of mvn package while working: it builds the folder from target/classes as the editor left them, through the project's own maven-war-plugin:exploded, and warns about classes javac compiled, recognised by their lambda method names. Both plugins fail when a class needs a newer Java than the build targets, which catches an editor reading the target level differently (Buildship ignores options.release). Checked through JDWP on WildFly 29 for Maven as for Gradle.

Eclipse writes the annotation processor's resources to Buildship's default output, bin/default. Making bin/main the default output as well clashed: Buildship in VS Code then renamed the classes' folder to bin/main_. So WTP publishes bin/default too.