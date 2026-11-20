import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The backend runs on 8080 (Docker). Proxying keeps the browser on one origin, so the session cookie just works.
export default defineConfig({
  plugins: [react()],
  // 7z-wasm finds its .wasm next to itself; pre-bundling would move the script away from it
  optimizeDeps: { exclude: ['7z-wasm'] },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
    },
  },
})
