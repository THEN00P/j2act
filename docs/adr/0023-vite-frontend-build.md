# Vite builds TypeScript, npm packages and Tailwind

Plain JavaScript keeps ADR 0022's rule: no build step, npm packages as mvnpm jars through import maps. Everything that needs a build (TypeScript, packages from node_modules, CSS modules, Tailwind) goes through Vite, as a web developer would set it up. If something works in a plain Vite project but not in j2act, j2act has the bug.

esbuild was the first TypeScript prototype. The runtime found a module's stylesheet by esbuild's own naming (Name.client.css), and esbuild lacks the plugin ecosystem Tailwind, PostCSS and framework islands rely on. Rsbuild would have brought webpack's conventions. A bundler-agnostic contract was rejected as well: Vite's manifest is the de facto format for backend integration (Laravel, Rails, Django, Symfony), and any tool that writes it is served the same way.

The build is plain Vite plus j2act's plugin:

    // vite.config.ts
    export default defineConfig({
      plugins: [tailwindcss(), j2act({ input: ["src/main/frontend/app.css"] })],
    });

- @j2act/vite makes every *.client.{ts,tsx,js,...} under src/main/java a Vite entry, named after its class's resource path (com/example/SalesChart.client), plus the page entries listed in input.
- The output goes onto the classpath with Vite's standard manifest, under META-INF/j2act/vite: build/j2act for Gradle, target/classes for Maven, detected from the build files.
- base is "./", so a stylesheet's url() and a chunk's imports stay relative, and the runtime serves them under its own path.
- The plugin only sets what the contract needs: entries, output folder, manifest and base. Anything else the user sets in vite.config wins.
- Source maps are written in dev builds only. For production, build.sourcemap: true serves them, and "hidden" writes them to disk where the runtime never serves them.

Built files are write-once. Their names carry a content hash, so a file that already exists is the same file, and it is left alone while a server may be reading it. Vite writes into a staging folder per process, new files are moved in, and the manifest goes live last in one rename. Files no manifest names any more are removed after ten minutes, since an open page may still import them. That makes any number of concurrent builds safe, which dev mode (ADR 0024) and IDE builds (ADR 0025) rely on. The spike's stress run served 173,601 files under two concurrent writers, and every one arrived complete.

The runtime reads the manifest. A client module is found from its class. Its script, its imported chunks and every stylesheet they carry are served as one graph under a content hash, following Vite's backend-integration rule (the entry's css, then that of every chunk it imports). Page entries go in the head with vite(...), which renders the tags an entry needs, like Laravel's @vite:

    head(title("Dashboard"), vite("src/main/frontend/app.css", "src/main/frontend/app.ts"))

Tailwind is opt-in and needs nothing from j2act: @tailwindcss/vite in vite.config and @import "tailwindcss" in a stylesheet. Tailwind v4 reads class names from every file the .gitignore does not exclude, the .java sources included. So withClass("px-4 py-2") works, as long as class names are complete strings, which is Tailwind's usual rule. That makes the .gitignore load-bearing: without build/ in it, Tailwind also reads the build output.

The client types move to .j2act/types at the project root, the same for Maven, Gradle and every IDE, as SvelteKit's .svelte-kit/types and React Router's .react-router/types are. The compilers' generated-source folders differ (target/generated-sources/annotations, build/generated/sources/annotationProcessor/java/main, and bin/generated-sources/annotations under Buildship), so tsconfig.json can no longer name one. It names the fixed folder once:

    "rootDirs": ["src/main/java", ".j2act/types"]

The processor writes these files outside the Filer, since the folder is the project's rather than the compiler's, so no build tool cleans it. The processor removes stale files itself: a class's file when the class no longer declares clients, and, once per compile, every file whose class the compiler no longer finds. It asks the compiler rather than the source folders, so an incremental build, which compiles only what changed, still sees every class; checked with Gradle's incremental compile and with the Eclipse compiler in VS Code.

Still open: a lazy import() split point, since relative dynamic imports are not served. The evidence is in docs/spikes/frontend-build.md.
