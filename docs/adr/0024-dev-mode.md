# Dev mode: the app runs its own Vite watcher

One launch, one server. Starting the app from the IDE or with bootRun is the whole dev loop; nobody starts Vite by hand, from a second folder, in the right order. Phoenix starts its esbuild and Tailwind watchers from the endpoint in dev, and Vaadin's dev mode builds the frontend from inside the servlet. j2act follows them.

Dev mode needs no flag, since a flag is forgotten in production builds or never found in dev ones. The annotation processor writes META-INF/j2act/dev.json into the class output, naming the project folder. It finds that folder from the Java source file, not the class output, because an IDE's output may sit inside a copy of the project. Dev mode is on when all of these hold:
- the token is a plain file on disk: a class folder, or an exploded deployment such as the one Eclipse publishes to WildFly (whose vfs: URLs name the real path);
- the project folder it names exists on this machine and holds a package.json;
- no test runner is on the starting thread's stack, as with Spring Boot devtools;
- -Dj2act.dev=false is not set. That is an escape hatch, not a setting anyone needs.

A token inside a WAR, a jar or a boot jar never counts, wherever the archive runs. So the token ships in archives harmlessly. Stripping it was tried and rejected: m2e-wtp applies the war plugin's packagingExcludes to Eclipse's own deployment, which turned dev mode off there.

In dev mode the app:
- runs @j2act/vite's runner, which is vite build --watch. It uses the project's own Node (Gradle's download in .gradle/nodejs, frontend-maven-plugin's in node/) or the PATH's.
- ties the watcher's lifetime to its own. The runner exits when its stdin pipe closes, which happens however the JVM ends, including the IDE's terminate button, which skips shutdown hooks. A lock file in .j2act keeps a second app instance from starting a second watcher. It lived in node_modules first, where it stopped an npm ci halfway on Windows.
- removes its JVM shutdown hook when the engine closes, and gives its threads no context class loader. A spike run found the hooks piling up across redeploys: each kept a whole undeployed application reachable, and HotSwap then also hit the stale copies of its classes. DevModeTest redeploys three times and wants all three class loaders collected.
- starts the runner again when it exits. The runner exits on its own with code 75 as soon as node_modules changes, and waits for a finished install before it loads anything. On Windows the native binaries it has loaded (rolldown, Tailwind, lightningcss) cannot be deleted while it runs, so without this an npm ci stopped halfway and the build failed on every edit; now npm ci with the app running works, and the watcher comes back with the new packages. A crash starts it again after a pause, three times at most.
- keeps the watcher's rebuilds away from build output and IDE folders (build, target, bin, .gradle, .settings, generated sources), with or without a .gitignore. Eclipse regenerates and copies files there on every build, and watching them loops.
- reads the Vite build from the project folder, not the classpath. Buildship leaves build/j2act off VS Code's classpath, and an exploded deployment holds only a copy.
- polls the manifest. When it changes, the app pushes every moved URL to the open pages. runtime.js swaps moved stylesheets in place and mounts a client again with its current props when its module moved. Java state, the session and the page stay.

Java edits reach the app by JVM HotSwap under the debugger, as ADR 0017 has it. A Tailwind class saved in a .java file rebuilds the CSS, and it applies once HotSwap has swapped the class. A normal, non-debug start swaps nothing.

The watcher skips type checking, as Vite's dev server does. Types are the editor's and the build's job (ADR 0025).

Node never runs in production and never for plain JavaScript. ADR 0009's "the framework never shells to node" now reads: only dev mode starts Node, and only for a project that exists on this machine.

HMR is deferred to the dev tooling work (ADR 0017). Live re-import already keeps Java state. What HMR would add is speed on large graphs and state-keeping framework refresh inside islands. When it comes, it goes through the app, as Vaadin proxies Vite's HTTP and HMR websocket through its servlet: one origin and one port. A second Vite port is what breaks Laravel setups behind Docker, WSL and proxies. The work is ours, not the user's: a Vite proxy in both adapters and websocket proxying on WildFly and Spring.
