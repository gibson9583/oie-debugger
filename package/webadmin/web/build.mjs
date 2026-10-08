import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

// One entry is required: authenticated WAR loading imports it from a Blob URL.
await build({
    entryPoints: [fileURLToPath(new URL('./plugin.jsx', import.meta.url))],
    outfile: fileURLToPath(new URL('./plugin.js', import.meta.url)),
    bundle: true,
    format: 'esm',
    target: 'es2022',
    jsxFactory: 'React.createElement',
    jsxFragment: 'React.Fragment',
    loader: { '.css': 'text' },
    external: ['@oie/web-shell'],
});
