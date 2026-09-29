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

With <extensions>true</extensions>, the Maven plugin will apply the pom boilerplate itself, as defaults: the processor path, the src/main/java resources, m2e.apt.activation, the TypeScript copy filter for Eclipse, and Node. Today the spike's pom still lists all of it.

Type errors fail gradle build, mvn package and mvn verify, the way a failing test does, but never compiling or starting the app. An IntelliJ Run of a Gradle project goes through Gradle, so a type check inside the bundle task would have blocked it.

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

The folder carries the classes the editor compiled whenever every source has a class in bin/main at least as new as itself; otherwise javac's, with a log line saying so. Resources always come from Gradle's build. This is what makes HotSwap work outside Eclipse's WTP. Editors built on the Eclipse Java language server (VS Code, Neovim, Zed) push classes compiled by the Eclipse compiler, and the JVM refuses them over javac's with "delete method not implemented", because the two name lambda methods differently. Checked through JDWP on WildFly 29: the javac build of an edit was refused, the Eclipse compiler's was accepted, and the page showed it on its next render without a reload. The alternative, a JVM with enhanced class redefinition, needs another JDK and is in the backlog.

Maven has the same problem without the separate folder: the editor and mvn share target/classes, and mvn compile, test and package recompile the whole module with javac whenever a file was added or removed, after a clean or a pull. So HotSwap there would fail seemingly at random. exploded-hotswap's Maven goal, mvn exploded-hotswap:exploded, runs instead of mvn package while working: it builds the folder from target/classes as the editor left them, through the project's own maven-war-plugin:exploded, and warns about classes javac compiled, recognised by their lambda method names. Both plugins fail when a class needs a newer Java than the build targets, which catches an editor reading the target level differently (Buildship ignores options.release). Checked through JDWP on WildFly 29 for Maven as for Gradle.

Eclipse writes the annotation processor's resources to Buildship's default output, bin/default. Making bin/main the default output as well clashed: Buildship in VS Code then renamed the classes' folder to bin/main_. So WTP publishes bin/default too.