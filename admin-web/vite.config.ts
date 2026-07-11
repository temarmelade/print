import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// В dev-режиме все /api-запросы проксируются на Spring-сервер, чтобы не воевать
// с CORS. Адрес берётся из VITE_API_PROXY (по умолчанию 8080).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "VITE_");
  const target = env.VITE_API_PROXY || "http://localhost:8080";
  return {
    plugins: [react()],
    server: {
      port: 5174,
      proxy: mode === "development" ? { "/api": { target, changeOrigin: true } } : undefined,
    },
  };
});
