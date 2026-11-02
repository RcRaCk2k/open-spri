/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// During development the API runs on :8080 (./gradlew :app:bootRun); in containers nginx proxies /api.
export default defineConfig({
  plugins: [react()],
  // the entry chunk holds React, Mantine and the login/setup pages (~180 kB gzip); all other pages load per route
  build: { chunkSizeWarningLimit: 700 },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/health": "http://localhost:8080",
    },
  },
  test: {
    include: ["src/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    setupFiles: ["src/test/setup.ts"],
  },
});
