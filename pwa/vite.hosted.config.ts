// Hosted-demo build: everything (JS, CSS, fonts) inlined into one index.html so it
// can be served from a single Supabase Edge Function response. `npm run build:hosted`.
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

export default defineConfig({
  plugins: [react(), viteSingleFile()],
  base: "./",
  build: {
    outDir: "dist-hosted",
    assetsInlineLimit: 100_000_000,
    cssCodeSplit: false,
  },
});
