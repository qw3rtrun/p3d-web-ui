import {defineConfig} from 'vite'
import {resolve} from 'path';
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    proxy: {
      '/custom': {
        target: "http://localhost:8080",
        changeOrigin: true,
        // rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  plugins: [vue()],
  build: {
    outDir: '../../build/dist',
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'web-ui.html'),
        nested: resolve(__dirname, 'somemodule/subindex.html'),
      }
    },
  }
})


