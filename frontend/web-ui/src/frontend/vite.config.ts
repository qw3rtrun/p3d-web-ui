import {defineConfig} from 'vite'
import {resolve} from 'path'
import vue from '@vitejs/plugin-vue'

/**
 * The UI is server-rendered pages with Vue islands in them, not a single-page application.
 *
 * So this build produces **one script and one stylesheet**, not HTML entry pages: Thymeleaf renders
 * the documents, and `main.ts` mounts components into the mount points it finds. The scaffold this
 * replaces built `web-ui.html` and a `somemodule/subindex.html`, which is the shape you want when
 * Vite owns the page - here it does not.
 *
 * **The filenames are deliberately not hashed.** A Thymeleaf template has to name the script it
 * loads, and a hashed name means either a manifest lookup in the template or a build step that
 * rewrites the HTML. Stable names cost cache-busting on deploy - recorded as debt in the
 * `vue-ui-dev` skill - and buy a template that simply says `/assets/p3d-ui.js`.
 *
 * Output lands in `build/dist`, which `copyVueFiles` in build.gradle copies into
 * `build/resources/main/static`, so the bundle is served from the same origin as the pages. That
 * matters: the session cookie and the CSRF cookie are same-origin.
 */
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
