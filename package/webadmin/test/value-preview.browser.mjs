// Presentation/expansion regression against local React matching the host, with no engine/API.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const scalar = (type, value) => ({ type, value });
const capturedText = 'MSH|^~\\&|SENDING APP|' + 'x'.repeat(140) + '\r\nPID|1||<captured>&value';
const fixture = { type: 'object', properties: {
    zero: scalar('number', 0), flag: scalar('boolean', false), empty: scalar('string', ''),
    nan: scalar('number', 'NaN'), infinity: scalar('number', 'Infinity'), negativeInfinity: scalar('number', '-Infinity'),
    nil: { type: 'null' }, missing: { type: 'undefined' }, getter: { type: 'accessor' }, repeated: { type: 'circular' },
    limited: { type: 'truncated' }, longString: { type: 'string', length: 3000, truncated: true },
    message: { ...scalar('string', capturedText), truncated: true },
    multiline: scalar('string', 'first\nsecond'),
    xmlText: { type: 'xml', name: '#text', value: capturedText },
    opaque: { type: 'opaque', className: 'com.example.vendor.ExternalResponse' },
    unavailable: { type: 'object', className: 'com.example.ObjectAtDepthLimit', truncated: true },
    partial: { type: 'object', properties: {}, truncated: true, notice: 'Value changed during inspection' },
    record: { type: 'object', properties: { count: scalar('number', 2) } },
    list: { type: 'object', length: 2, properties: { 0: scalar('string', 'first'), 1: { type: 'object', properties: { kept: scalar('boolean', false) } } } },
    xml: { type: 'xml', name: 'PID', attributes: [{ type: 'xml', name: 'id', children: [{ type: 'xml', name: '#text', value: 'synthetic' }] }], children: [{ type: 'xml', name: 'PID.5', text: { type: 'xml', name: '#text', value: 'Alice & Bob' } }] },
    xmlList: { type: 'xml-list', properties: { 0: { type: 'xml', name: '#text', value: 'one' }, 1: { type: 'xml', name: '#text', value: 'two' } }, truncated: true },
    xmlMissingNodes: { type: 'xml', name: 'root', attributes: [null], children: [null] },
    escaped: scalar('string', '<img src=x onerror="window.previewExecuted=true">'),
    function: { type: 'function', className: 'org.mozilla.javascript.InterpretedFunction' },
} };
const entry = `import React from 'react';import {createRoot} from 'react-dom/client';import {PropertiesPreview} from ${JSON.stringify(fileURLToPath(new URL('../web/value-preview.jsx', import.meta.url)))};
const root=createRoot(document.getElementById('root'));window.fixture=${JSON.stringify(fixture)};window.renderPreview=value=>root.render(React.createElement(PropertiesPreview,{value,emptyText:'No local variables.'}));window.renderPreview(window.fixture);`;
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
    const row = name => page.locator('.dbg-properties > .dbg-value').filter({ has: page.locator('.dbg-value-name').filter({ hasText: new RegExp('^' + name + '$') }) });
    await row('zero').waitFor();
    for (const [name, text] of Object.entries({ zero: '0', flag: 'false', empty: '""', nan: 'NaN', infinity: 'Infinity', negativeInfinity: '-Infinity', nil: 'null', missing: 'undefined', getter: 'Getter not invoked', repeated: 'Already shown', limited: 'Preview truncated' }))
        assert.equal(await row(name).locator('.dbg-value-summary').textContent(), text);
    assert.match(await row('longString').innerText(), /3000 characters.*Value not captured/);
    assert.match(await row('longString').innerText(), /Preview truncated/);
    assert.equal(await row('opaque').locator('.dbg-value-summary').textContent(), 'ExternalResponse · Not inspected');
    assert.equal(await row('opaque').locator('.dbg-value-summary').getAttribute('title'), 'com.example.vendor.ExternalResponse');
    assert.match(await row('unavailable').innerText(), /Not inspected.*Preview truncated/);
    assert.match(await row('partial').innerText(), /Preview truncated.*Value changed during inspection/);
    assert.equal(await row('list').locator(':scope > summary .dbg-value-summary').textContent(), 'Array(2)');
    assert.match(await row('xmlList').locator(':scope > summary').innerText(), /XML list.*Preview truncated/);
    assert.equal(await page.locator('details[open]').count(), 0);
    for (const name of ['message', 'multiline', 'xmlText']) {
        assert.ok((await row(name).locator(':scope > summary .dbg-value-summary').textContent()).length <= 120);
        assert.equal(await row(name).locator('.dbg-value-full').isVisible(), false);
        await row(name).locator(':scope > summary').click();
        assert.equal(await row(name).locator('.dbg-value-full').textContent(), fixture.properties[name].value, 'expanded captured text is lossless');
    }
    assert.match(await row('message').locator(':scope > summary').innerText(), /Preview truncated/);
    await row('record').locator(':scope > summary').click();
    await row('list').locator(':scope > summary').click();
    await row('list').locator('.dbg-value-children > details > summary').click();
    await page.evaluate(() => { const next = JSON.parse(JSON.stringify(fixture)); next.properties.record.properties.count.value = 99; renderPreview(next); });
    await page.waitForFunction(() => [...document.querySelectorAll('details[open]')].some(detail => detail.textContent.includes('99')));
    assert.equal(await page.locator('details[open]').count(), 6, 'poll replacement retains text, parent, and nested expansion');
    assert.equal(await row('message').locator('.dbg-value-full').isVisible(), true);
    await row('xml').locator(':scope > summary').click();
    assert.match(await row('xml').innerText(), /@id/);
    await row('xml').locator('.dbg-value-children > details').filter({ hasText: '@id' }).locator(':scope > summary').click();
    assert.match(await row('xml').innerText(), /synthetic/);
    await row('xml').locator('.dbg-value-children > details').filter({ hasText: 'PID.5' }).locator(':scope > summary').click();
    assert.match(await row('xml').innerText(), /Alice & Bob/);
    await row('xmlList').locator(':scope > summary').click();
    assert.match(await row('xmlList').innerText(), /one.*two/s);
    await row('xmlMissingNodes').locator(':scope > summary').click();
    assert.equal(await row('xmlMissingNodes').locator('.dbg-value-children .dbg-value-summary').allTextContents().then(values => values.join(',')), 'null,null');
    assert.equal(await page.locator('img').count(), 0);
    assert.equal(await page.evaluate(() => window.previewExecuted), undefined);
    assert.match(await row('escaped').innerText(), /<img src=x/);
    await page.evaluate(() => renderPreview({ type: 'object', properties: {} }));
    await page.getByText('No local variables.', { exact: true }).waitFor();
    await page.evaluate(() => renderPreview({ type: 'object', className: 'java.lang.Object' }));
    await page.getByText('Object · Not inspected', { exact: true }).waitFor();
    assert.equal(await page.getByText('No local variables.', { exact: true }).count(), 0);
    assert.deepEqual(errors, []);
    console.log('Value preview browser regression passed: scalar/marker fidelity, omitted vs empty properties, bounded XML/array details, lossless expandable text, visible notices, escaping, and expansion retained across polling.');
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
