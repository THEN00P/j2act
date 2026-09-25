// What the bundler gives CSS imports, as in Vite's client types: a CSS module's local class names,
// and nothing for a plain stylesheet such as "quill/dist/quill.snow.css".
declare module "*.module.css" {
  const classes: { readonly [name: string]: string };
  export default classes;
}

declare module "*.css";
