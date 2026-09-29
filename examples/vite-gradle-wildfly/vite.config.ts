import { defineConfig } from "vite";
import tailwindcss from "@tailwindcss/vite";
import j2act from "@j2act/vite";

export default defineConfig({
  plugins: [tailwindcss(), j2act({ input: ["src/main/frontend/app.css"] })],
});
