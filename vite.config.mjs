import { defineConfig } from 'vite';

export default defineConfig({
  publicDir: false,
  build: {
    outDir: 'public/compiled/bundle',
    emptyOutDir: true,
    sourcemap: true,
    rollupOptions: {
      input: 'js-src/index.js',
      output: {
        entryFileNames: 'main.js',
        assetFileNames: 'main.[ext]',
      },
    },
  },
});
