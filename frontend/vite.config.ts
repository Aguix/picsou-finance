import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'
import path from 'path'
import fs from 'node:fs'

const localCertPath = path.resolve(__dirname, '.local/certs/picsou-local-cert.pem')
const localKeyPath = path.resolve(__dirname, '.local/certs/picsou-local-key.pem')
// Hybrid HTTPS: Uses mkcert (.pem files) for a trusted local SSL certificate (green padlock).
// If missing (e.g., in Docker or for other devs), falls back to the basicSsl plugin.
const hasLocalCerts = fs.existsSync(localCertPath) && fs.existsSync(localKeyPath)
const localHttps = hasLocalCerts
  ? {
      cert: fs.readFileSync(localCertPath),
      key: fs.readFileSync(localKeyPath),
    }
  : undefined

export default defineConfig({
  plugins: [react(), tailwindcss(), ...(!hasLocalCerts ? [basicSsl()] : [])],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
    // Force a single React instance. Without this a transitive dep can resolve
    // its own copy, which surfaces as "dispatcher is null" / "can't access
    // useContext" at runtime (two Reacts → hooks talk to the wrong renderer).
    dedupe: ['react', 'react-dom'],
  },
  optimizeDeps: {
    // Pre-bundle the whole runtime dependency surface on first start. Routes are
    // lazy-loaded, so navigating used to let Vite discover a new dep mid-session,
    // re-optimize, and soft-reload — leaving react and react-dom on mismatched
    // optimize-deps hashes (two React instances → "dispatcher is null"). Listing
    // everything up front makes the first crawl comprehensive and avoids the
    // mid-session re-optimization that triggered the crash.
    include: [
      'react',
      'react-dom',
      'react-dom/client',
      'react/jsx-runtime',
      'react-router-dom',
      'react-i18next',
      'i18next',
      'i18next-browser-languagedetector',
      '@tanstack/react-query',
      'react-hook-form',
      '@hookform/resolvers/zod',
      'axios',
      'zod',
      'zustand',
      'recharts',
      'lucide-react',
      'sonner',
      'next-themes',
      'class-variance-authority',
      'clsx',
      'tailwind-merge',
      'input-otp',
    ],
  },
  server: {
    port: 5173,
    https: localHttps,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
