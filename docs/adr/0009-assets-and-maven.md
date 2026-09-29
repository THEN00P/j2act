# Assets duality and Maven build

Assets are dual-mode: plain withStylesheet/withScript declarations for traditionalists (no node ever required), or an rslib/vite manifest hook (hashed names, dev HMR) for managed setups. Tailwind flows through withClass(cn(...)) identically in both. Production and plain JavaScript never need Node; only dev mode starts it, for a project on this machine (ADR 0024). The managed mode is Vite with the manifest contract of ADR 0023.

Build is Maven multi-module (core, html, router, spring, jakarta, ui). Maven is the distribution standard our consumers build with — Spring and especially WildFly/Jakarta shops — plus canonical Central publishing and reproducible builds. Gradle comfort is author-side cost; the poms are written once. That is j2act's own build: for applications, Gradle is as first-class as Maven (ADR 0025).
