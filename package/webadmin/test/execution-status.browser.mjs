// The real component and host React, without touching an engine or active session.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const source = { id: 'top', hash: 'hash', generation: 2, name: 'channel_Filter_Transformer_0', kind: 'transformer', text: 'verified source',
    authoredMappings: [{ sourceId: 'top', kind: 'transformer', name: 'Normalize patient ID', generatedStartLine: 10, generatedEndLine: 12 }] };
const frame = { index: 0, sourceId: source.id, sourceHash: source.hash, sourceGeneration: source.generation, line: 10 };
const state = { source, sources: [], session: { id: 's1', status: 'paused', reason: 'entry', connectorId: '0', pauseGeneration: 1, frames: [frame] } };
const caller = { ...source, id: 'caller', name: 'selected-caller', kind: 'preprocessor', authoredMappings: [] };
const entry = `import React from 'react';import {createRoot} from 'react-dom/client';import {ExecutionStatus,executionStatusModel} from ${JSON.stringify(fileURLToPath(new URL('../web/execution-status.jsx', import.meta.url)))};
const root=createRoot(document.getElementById('root'));window.statusModel=executionStatusModel;window.renderStatus=state=>root.render(React.createElement(ExecutionStatus,{state,channelName:'ADT inbound'}));window.renderStatus(${JSON.stringify(state)});`;
const bundle = await build({ stdin: { contents: entry, resolveDir: packageRoot }, bundle: true, write: false, format: 'esm', define: { 'process.env.NODE_ENV': '"production"' }, plugins: [{ name: 'host-fixture', setup(build) {
    build.onResolve({ filter: /^@oie\/web-shell$/ }, () => ({ path: 'host', namespace: 'fixture' }));
    build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: "import React from 'react';export const platform={React};", resolveDir: packageRoot }));
} }] });
const server = createServer((req, res) => { res.setHeader('Content-Type', req.url === '/main.js' ? 'application/javascript' : 'text/html'); res.end(req.url === '/main.js' ? bundle.outputFiles[0].text : '<!doctype html><html><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>'); });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage(), errors = [];
    page.setDefaultTimeout(10000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.getByText('Paused on entry · Source transformer', { exact: true }).waitFor();
    assert.equal(await page.locator('.dbg-execution-source').evaluate(el => el.open), false);
    await page.getByText('Deployed script', { exact: true }).click();
    assert.equal(await page.locator('.dbg-execution-source code').innerText(), source.name);
    const model = (next, cached = null) => page.evaluate(([value, cached]) => statusModel(value, 'ADT inbound', cached), [next, cached]);
    const session = patch => ({ ...state, session: { ...state.session, ...patch } });
    for (const [reason, expected] of Object.entries({ entry: 'Paused on entry', breakpoint: 'Paused at breakpoint', step: 'Paused after step', pause: 'Paused by request', exception: 'Paused on exception', debugger: 'Paused at debugger statement', unknown: 'Paused' }))
        assert.equal((await model(session({ reason }))).title, `${expected} · Source transformer`);
    assert.match((await model(session({ reason: 'exception', exception: 'org.example.ScriptException' }))).help, /org.example.ScriptException/);
    assert.match((await model(session({ connectorId: '3', reason: 'breakpoint' }))).title, /Destination 3 transformer$/);
    const identified = await model(session({ messageId: '42', sendAttempts: 2 }));
    assert.match(identified.location, /Message 42 · Attempt 2/);
    assert.doesNotMatch((await model(session({ messageId: '42', sendAttempts: 1 }))).location, /Attempt/);
    const rearmed = await model(session({ status: 'armed', reason: 'Execution completed', messageId: '42', sendAttempts: 2 }));
    assert.equal(rearmed.location, '');
    assert.doesNotMatch(JSON.stringify(rearmed), /Message 42|Attempt 2/);
    const wrapper = await model(session({ frames: [{ ...frame, line: 2 }] }));
    assert.equal(wrapper.title, 'Paused on entry · Source filter / transformer');
    assert.match(wrapper.location, /Generated script entry · Generated line 2/);
    assert.doesNotMatch(wrapper.location, /Normalize patient ID/);
    for (const kind of ['preprocessor', 'postprocessor', 'response', 'javascript', 'code-template']) {
        const result = await model({ ...state, source: { ...source, kind, authoredMappings: [] } });
        assert.doesNotMatch(result.title, /filter \/ transformer/);
        assert.match(result.title, new RegExp(kind === 'response' ? 'response transformer' : kind === 'javascript' ? 'JavaScript' : kind.replace('-', ' ')));
    }
    const listedSource = { ...source }; delete listedSource.authoredMappings; delete listedSource.text;
    const loading = await model({ ...state, source: caller, sources: [listedSource] });
    assert.match(loading.title, /Source filter \/ transformer/);
    assert.match(loading.location, /Generated line 10.*Loading script details…/);
    assert.doesNotMatch(loading.location, /Normalize patient ID|selected-caller/);
    for (const changed of [{ hash: 'old' }, { generation: 1 }, { id: 'wrong' }]) {
        const stale = await model({ ...state, source: { ...source, ...changed } });
        assert.equal(stale.title, 'Paused on entry · Source processing');
        assert.equal(stale.sourceName, undefined);
        assert.doesNotMatch(stale.location, /Normalize patient ID|channel_Filter/);
    }
    const wrongMapping = await model({ ...state, source: { ...source, authoredMappings: [{ ...source.authoredMappings[0], sourceId: 'other' }] } });
    assert.doesNotMatch(wrongMapping.location, /Normalize patient ID/);
    const missing = await model(session({ frames: [] }));
    assert.match(missing.location, /Pause location unavailable.*Waiting for paused frame details/);
    assert.doesNotMatch(missing.location, /Generated line|Normalize patient ID/);
    for (const status of ['running', 'armed']) {
        const result = await model(session({ status }));
        assert.equal(result.location, '');
        assert.doesNotMatch(result.help, /completed/);
    }
    const completed = await model(session({ status: 'armed', reason: 'Execution completed' }));
    assert.match(completed.help, /Last execution completed/);
    assert.doesNotMatch(completed.help, /message.*complete/i);
    assert.equal((await model({ ...state, lost: true })).location, '');
    assert.equal((await model({ ...state, pending: { action: 'continue' } })).title, 'Execution state pending');
    assert.match((await model({ ...state, pending: { action: 'inspect' } })).title, /Paused on entry/);
    assert.equal(await model({ session: null }), null);
    assert.equal(await model(session({ status: 'closed' })), null);

    // Selecting a caller changes source metadata; the confirmed top location stays stable.
    await page.evaluate(next => renderStatus(next), { ...state, source: caller });
    await page.waitForTimeout(20);
    assert.match(await page.locator('.dbg-execution-location').innerText(), /Normalize patient ID.*Generated line 10/);
    assert.doesNotMatch(await page.locator('.dbg-execution').innerText(), /selected-caller|Loading script details/);
    // Poll replacements and a new line in the same verified source retain the cache.
    await page.evaluate(next => renderStatus(next), { ...session({ reason: 'step', frames: [{ ...frame, line: 11 }] }), source: null });
    await page.getByText('Paused after step · Source transformer', { exact: true }).waitFor();
    assert.match(await page.locator('.dbg-execution-location').innerText(), /Generated line 11/);
    // Different compiled identity cannot reuse old authored metadata.
    await page.evaluate(next => renderStatus(next), { ...session({ frames: [{ ...frame, sourceGeneration: 3 }] }), source: caller });
    await page.getByText('Paused on entry · Source processing', { exact: true }).waitFor();
    assert.match(await page.locator('.dbg-execution-location').innerText(), /Loading script details/);
    assert.doesNotMatch(await page.locator('.dbg-execution-location').innerText(), /Normalize patient ID/);
    // Lost/closed/new-session transitions clear all cached stop context.
    await page.evaluate(next => renderStatus(next), state);
    await page.getByText('Paused on entry · Source transformer', { exact: true }).waitFor();
    await page.evaluate(next => renderStatus(next), { ...state, lost: true });
    await page.getByText('Connection lost · Execution state unknown', { exact: true }).waitFor();
    assert.equal(await page.locator('.dbg-execution-location').count(), 0);
    await page.evaluate(next => renderStatus(next), { ...state, source: caller });
    await page.getByText('Paused on entry · Source processing', { exact: true }).waitFor();
    await page.evaluate(next => renderStatus(next), state);
    await page.getByText('Paused on entry · Source transformer', { exact: true }).waitFor();
    await page.evaluate(next => renderStatus(next), { ...session({ id: 's2' }), source: caller });
    await page.getByText('Paused on entry · Source processing', { exact: true }).waitFor();
    assert.equal(await page.locator('[role=status]').getAttribute('aria-live'), 'polite');
    assert.equal(await page.locator('[role=status]').getAttribute('aria-atomic'), 'true');
    await page.evaluate(next => renderStatus(next), session({ status: 'closed' }));
    await page.waitForFunction(() => !document.querySelector('.dbg-execution'));
    assert.deepEqual(errors, []);
    console.log('Execution status browser regression passed: reason/stage matrix, exact source identity, authored ranges, caller selection/polling, lifecycle cache reset, pending/lost/running/armed states, and live status semantics.');
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
