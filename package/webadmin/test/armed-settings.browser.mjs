// Read-only session settings with local React matching the host; no engine or active session.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const target = { id: 'target', name: 'Original deployed transformer' };
const state = { armOptions: { channelId: 'channel', messageId: '42', connectorId: 0, sourceId: target.id,
    breakOnEntry: false, pauseOnExceptions: true, includeLifecycle: true, evaluationEnabled: true, maxPauseMillis: 30000 },
    sources: [target], source: { id: 'selected', name: 'Selected caller script' },
    session: { id: 's1', channelId: 'channel', status: 'armed', messageId: '99', connectorId: '3', includeLifecycle: true, evaluationEnabled: true, maxPauseMillis: 30000 } };
const entry = `import React from 'react';import {createRoot} from 'react-dom/client';import {SessionSettings,sessionSettingsModel} from ${JSON.stringify(fileURLToPath(new URL('../web/armed-settings.jsx', import.meta.url)))};
const root=createRoot(document.getElementById('root'));window.settingsModel=sessionSettingsModel;window.renderSettings=(state,channelName='ADT inbound')=>root.render(React.createElement(SessionSettings,{state,channelName}));window.renderSettings(${JSON.stringify(state)});`;
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
    await page.getByRole('heading', { name: 'Session settings', exact: true }).waitFor();
    const field = name => page.locator('.dbg-settings-grid > div').filter({ has: page.locator('dt').filter({ hasText: new RegExp('^' + name + '$') }) }).locator('dd');
    const model = next => page.evaluate(value => settingsModel(value, 'ADT inbound'), next);
    const fields = async next => Object.fromEntries((await model(next)).fields);
    assert.equal(await field('Message filter').innerText(), '42');
    assert.equal(await field('Connector filter').innerText(), 'Source (0)');
    assert.equal(await field('Deployed source').innerText(), target.name);
    assert.equal(await field('Break on entry').innerText(), 'Off');
    assert.equal(await field('Pause on exceptions').innerText(), 'On');
    assert.equal(await page.locator('input,button,select,textarea').count(), 0, 'settings are plaintext, not disabled form controls');
    const original = await fields(state);
    for (const status of ['running', 'paused', 'armed']) {
        const polled = { ...state, session: { ...state.session, status, messageId: '101', connectorId: '8' },
            source: { id: 'new-selection', name: 'Another selected script' } };
        assert.deepEqual(await fields(polled), original, 'poll/current execution identity cannot replace original target filters');
        await page.evaluate(next => renderSettings(next), polled);
        assert.equal(await field('Message filter').innerText(), '42');
        assert.equal(await field('Deployed source').innerText(), target.name);
    }
    const defaults = { session: { id: 'defaults', status: 'armed' }, armOptions: { channelId: 'channel' }, sources: [] };
    const values = await fields(defaults);
    assert.deepEqual(values, { Channel: 'ADT inbound', 'Message filter': 'Any message', 'Connector filter': 'Any connector',
        'Script kind': 'Any kind', 'Deployed source': 'Any script', 'Break on entry': 'On', 'Pause on exceptions': 'Off',
        'Lifecycle / scripts without a message': 'Off', Evaluation: 'Off', 'Maximum pause': '60 seconds' });
    const overridden = await fields({ ...state, session: { ...state.session, includeLifecycle: false, evaluationEnabled: false, maxPauseMillis: 1000 } });
    assert.equal(overridden['Lifecycle / scripts without a message'], 'Off');
    assert.equal(overridden.Evaluation, 'Off');
    assert.equal(overridden['Maximum pause'], '1 second');
    for (const [value, expected] of [[0, '1 second'], [-2000, '1 second'], [999999, '120 seconds'], ['1000', '60 seconds']])
        assert.equal((await fields({ ...defaults, armOptions: { ...defaults.armOptions, maxPauseMillis: value } }))['Maximum pause'], expected);
    const global = await fields({ ...defaults, armOptions: { channelId: 'global' } });
    assert.equal(global.Channel, 'Global scripts');
    assert.equal(global['Lifecycle / scripts without a message'], 'On');
    assert.equal((await fields({ ...defaults, armOptions: { ...defaults.armOptions, connectorId: 2 } }))['Connector filter'], 'Destination 2');
    assert.equal((await fields({ ...defaults, armOptions: { ...defaults.armOptions, messageId: 0 } }))['Message filter'], '0');
    assert.equal((await fields({ ...state, sources: [], source: target }))['Deployed source'], target.name);
    assert.equal((await fields({ ...state, sources: [], source: { id: 'other', name: 'Unrelated script' } }))['Deployed source'], target.id);
    assert.equal((await fields({ ...defaults, source: target }))['Deployed source'], 'Any script');
    assert.equal((await model({ ...state, armOptions: null })).fields, null);
    await page.evaluate(next => renderSettings(next), { ...state, armOptions: null });
    await page.getByText('Settings unavailable for this session.', { exact: true }).waitFor();
    assert.equal(await page.locator('dl').count(), 0);
    await page.evaluate(next => renderSettings(next), { ...state, lost: true });
    await page.getByRole('heading', { name: 'Last confirmed session settings', exact: true }).waitFor();
    assert.equal(await field('Message filter').innerText(), '42');
    assert.match(await page.locator('.dbg-session-settings').innerText(), /before the connection was lost/);
    await page.evaluate(next => renderSettings(next), { ...state, session: { ...state.session, status: 'closed' } });
    await page.getByRole('heading', { name: 'Ended session settings', exact: true }).waitFor();
    assert.match(await page.locator('.dbg-session-settings').innerText(), /Clear the session to choose settings and arm again/);
    const hostile = '<img src=x onerror="window.settingsExecuted=true">';
    await page.evaluate(([next, label]) => renderSettings(next, label), [{ ...defaults, armOptions: { channelId: 'channel', sourceId: 'x', messageId: hostile }, sources: [{ id: 'x', name: hostile }] }, hostile]);
    await page.getByRole('heading', { name: 'Session settings', exact: true }).waitFor();
    assert.equal(await field('Message filter').innerText(), hostile);
    assert.equal(await page.locator('img').count(), 0);
    assert.equal(await page.evaluate(() => window.settingsExecuted), undefined);
    await page.evaluate(() => renderSettings({ session: null }));
    await page.waitForFunction(() => !document.querySelector('.dbg-session-settings'));
    assert.deepEqual(errors, []);
    console.log('Armed settings browser regression passed: original filters, polling/selection independence, defaults/effective overrides, duration bounds, global/source identities, unavailable/lost/closed states, plaintext semantics and escaping.');
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
