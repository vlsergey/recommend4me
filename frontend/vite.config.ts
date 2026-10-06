import path from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const BACKEND = "http://localhost:8095";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "./src") },
  },
  build: {
    sourcemap: true,
  },
  server: {
    port: 5173,
    // Development talks to the running backend: the data lives in its databases, and the
    // browser extension's archive is served by it too
    proxy: {
      "/api": BACKEND,
      "/extension": BACKEND,
    },
  },
});
