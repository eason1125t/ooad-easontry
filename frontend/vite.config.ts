// Builds the Vue UI and proxies same-origin API calls to the Spring backend in development.
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  build: { emptyOutDir: false }, // Preserve existing generated files; no bulk cleanup is performed.
  server: { port: 5173, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8080' } },
});
