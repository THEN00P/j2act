// Packages the import map serves from mvnpm jars (ADR 0022). The jars carry no .d.ts, so the
// editor sees them untyped; `npm i -D quill chart.js` in this folder gives their full types.
declare module "quill/dist/quill.js";
declare module "chart.js/auto";
