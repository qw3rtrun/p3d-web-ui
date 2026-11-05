import {defineConfig} from 'vite'
import {resolve} from 'path'
import vue from '@vitejs/plugin-vue'

// The UI is server-rendered pages with Vue islands, not a single-page application, so this build
// produces one script and one stylesheet rather than HTML entry pages - Thymeleaf renders the
// documents and main.ts mounts into whatever mount points it finds.
//
// Filenames are deliberately not hashed: a Thymeleaf template has to name the script it loads, and
// a hashed name would need either a manifest lookup there or a build step rewriting the HTML -
// stable names cost cache-busting on deploy (recorded as debt in the vue-ui-dev skill).
//
// Output lands in build/dist, which copyVueFiles in build.gradle copies into
// build/resources/main/static, so the bundle is served from the same origin as the pages - which
// matters, since the session and CSRF cookies are same-origin.
export default defineConfig({
    plugins: [vue()],

    server: {
        // `npm run dev` serves the bundle while the Spring application serves everything else.
        proxy: {
            '/api-farm': {target: 'http://localhost:8080', changeOrigin: true},
            '/api-printer': {target: 'http://localhost:8080', changeOrigin: true},
            '/api-manager': {target: 'http://localhost:8080', changeOrigin: true},
            '/api-users': {target: 'http://localhost:8080', changeOrigin: true},
            '/login': {target: 'http://localhost:8080', changeOrigin: true},
        },
    },

    build: {
        outDir: '../../build/dist',
        emptyOutDir: true,
        rollupOptions: {
            input: {'p3d-ui': resolve(import.meta.dirname, 'src/main.ts')},
            output: {
                entryFileNames: 'assets/[name].js',
                chunkFileNames: 'assets/[name].js',
                assetFileNames: 'assets/[name][extname]',
            },
        },
    },
})
