import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// In development, /api/v1/alerts etc. go to the alerts service, everything else to core.
const alerts = { target: 'http://localhost:8081', changeOrigin: true };
const core = { target: 'http://localhost:8080', changeOrigin: true };

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'StockSafe',
        short_name: 'StockSafe',
        description: 'Stop waste. Stop stockouts.',
        theme_color: '#0f766e',
        background_color: '#f8fafc',
        display: 'standalone',
        icons: [{ src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
      },
      workbox: { navigateFallbackDenylist: [/^\/api\//] },   // never cache API calls
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/alerts': alerts,
      '/api/v1/alert-rules': alerts,
      '/api/v1/forecast': alerts,
      '/api/v1/notifications': alerts,
      '/api': core,
    },
  },
});
