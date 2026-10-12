import vue from "@vitejs/plugin-vue";
import { defineConfig, loadEnv } from "vite";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxy = {
    "/api": {
      target: env.API_PROXY_TARGET || "http://127.0.0.1:8080",
      changeOrigin: true,
    },
  };
  return {
    plugins: [vue()],
    server: { host: "127.0.0.1", proxy },
    preview: { host: "127.0.0.1", proxy },
  };
});
