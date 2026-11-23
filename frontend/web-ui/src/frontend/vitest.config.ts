import {defineConfig} from 'vitest/config'

// Separate from vite.config.ts on purpose: the production build has no notion of a test
// environment, and vitest's own config type (not vite's) is what `test` below needs. Node is
// enough - the only logic under test so far (client.ts's stream pooling) touches no DOM, so
// jsdom/happy-dom are not pulled in as a dependency until a component actually needs one.
export default defineConfig({
    test: {
        environment: 'node',
    },
})
