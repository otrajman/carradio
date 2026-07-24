import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // GitHub Pages serves from /carradio/ — CI sets PWA_BASE; local stays "/"
  base: process.env.PWA_BASE ?? "/",
  server: { host: true, port: 5173 },
});
