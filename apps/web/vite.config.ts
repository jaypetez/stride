import path from 'node:path';
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

export default defineConfig(({ mode }) => {
  // Read the API settings from the shell or the repo-root .env (server-side
  // only: nothing here is exposed to the browser bundle).
  const env = { ...loadEnv(mode, repoRoot, 'STRIDE_API_'), ...process.env };
  const port = env.STRIDE_API_PORT || '8720';
  const token = env.STRIDE_API_TOKEN?.trim();

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          // 127.0.0.1, not `localhost`: the API binds IPv4 loopback by default
          // and `localhost` may resolve to ::1 first.
          target: `http://127.0.0.1:${port}`,
          // Rewrites Host to 127.0.0.1:<port>, which the API's DNS-rebinding
          // Host allowlist accepts.
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api/, ''),
          // When the API requires STRIDE_API_TOKEN, the dev proxy attaches it so
          // the token never reaches the browser.
          headers: token ? { authorization: `Bearer ${token}` } : undefined,
        },
      },
    },
  };
});
