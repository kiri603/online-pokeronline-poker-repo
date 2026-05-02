import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import path from "path";

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  // Allow a developer to override the backend target through VITE_DEV_BACKEND.
  // Local development must default to the local Spring Boot server so frontend
  // changes and backend changes are tested against the same codebase.
  const devBackend = env.VITE_DEV_BACKEND || "http://127.0.0.1:8080";
  const wsBackend = devBackend.replace(/^http/, "ws");

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },
    server: {
      host: "127.0.0.1",
      port: 5173,
      proxy: {
        "/api": {
          target: devBackend,
          changeOrigin: true,
          secure: false,
        },
        "/ws": {
          target: wsBackend,
          ws: true,
          changeOrigin: true,
          secure: false,
        },
      },
    },
  };
});
