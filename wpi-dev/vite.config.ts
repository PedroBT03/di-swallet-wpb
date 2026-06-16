import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const wpbTarget = process.env.VITE_WPB_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: wpbTarget, changeOrigin: true },
      "/openid4vp": { target: wpbTarget, changeOrigin: true },
      "/openid4vci": { target: wpbTarget, changeOrigin: true },
      "/actuator": { target: wpbTarget, changeOrigin: true },
    },
  },
});
