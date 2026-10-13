// Full plugin against an in-process mock host. No live engine or messages.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const mock = `
import React from 'react';
import {createRoot} from 'react-dom/client';
const mode=new URLSearchParams(location.search).get('mode');
let target=mode==='view-only'?null:{channelId:'channel-1'},session=null,serial=0;
const calls=[],source={id:'source-1',name:'channel-1_transformer_0',kind:'transformer',hash:'hash',generation:1,text:'value;',lineCount:1,breakableLines:[1],authoredMappings:[]};
window.fixture={calls,execution:()=>{session={...session,status:'running',messageId:'901',connectorId:7};},close:()=>{session={...session,status:'closed',closeReason:'maximum pause expired'};},evict:()=>{session=null;}};
const api={
 get:async(path)=>{calls.push(['GET',path]);if(path.endsWith('/capabilities'))return JSON.stringify({available:true,apiVersion: 1, commandProtocol: 2,armRequestExpiresAt:30000,controlAllowed:mode!=='view-only',evaluationEnabled:mode!=='engine-disabled',evaluationAllowed:mode==='engine-disabled',globalAllowed:mode!=='view-only'});if(path.endsWith('/sources'))return JSON.stringify([source]);if(path.includes('/sources/'))return JSON.stringify(source);if(path.includes('/sessions/'))return JSON.stringify(session);throw new Error('Unexpected GET '+path);},
 post:async(path,text)=>{const body=JSON.parse(text);calls.push(['POST',path,body]);if(path.endsWith('/sessions')){session={id:'session-'+(++serial),channelId:body.channelId,status:'armed',pauseGeneration:0,maxPauseMillis:body.maxPauseMillis,includeLifecycle:body.includeLifecycle,evaluationEnabled:body.evaluationEnabled};return JSON.stringify(session);}if(path.endsWith('/heartbeat'))return JSON.stringify(session);throw new Error('Unexpected POST '+path);},
 del:async(path)=>{calls.push(['DELETE',path]);if(!session)throw Object.assign(new Error('Debug session is unavailable to this owner/tab'),{status:403});session=null;return '{}';},
 channels:{list:async()=>[{id:'channel-1',name:'Options fixture'}]},onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}
};
export const platform={React,api,checkTask:()=>true,oie:{uuid:()=>crypto.randomUUID()},store:{getState:key=>key==='user'?{id:'user-1'}:key==='debuggerTarget'?target:null,setState:(key,value)=>{if(key==='debuggerTarget')target=value;},subscribe:()=>()=>{}},router:{navigate:()=>{}},registerIcon:()=>{},registerNavItem:()=>{},registerChannelAction:()=>{},registerMessageAction:()=>{},reactView:component=>component,registerView:(path,View)=>createRoot(document.getElementById('root')).render(React.createElement(View)),createCodeEditor:opts=>{const el=document.createElement('div'),area=document.createElement('textarea');area.readOnly=true;area.value=opts.value;el.append(area);return{el,area,dispose:()=>{}}}};
`;

// Minimal host checkbox rules copied from oie-web-client's app.css. Keeping the
// components layer is essential: unlayered plugin input styles outrank it.
const css = `
:root{--font-ui:system-ui;--font-mono:monospace;--text:#d9e2ea;--text-dim:#8a99a8;--bg1:#111922;--bg2:#16212c;--bg3:#1c2a38;--line:#233140;--line-strong:#2f4254;--accent:#58a6e8;--accent-ink:#06141f;--warn:#f0b541;--err:#ff5f5f;--ok:#3ddc84;color-scheme:dark}
:root[data-theme=light]{--text:#172331;--text-dim:#536475;--bg1:#fff;--bg2:#f0f4f8;--bg3:#e3ebf4;--line:#d6e0ea;--line-strong:#b3c2d1;--accent:#1765a9;--accent-ink:#fff;color-scheme:light}
*{box-sizing:border-box}body{margin:0;background:var(--bg1)}
@layer components {
 input[type=checkbox],input[type=radio]{appearance:none;position:relative;flex:none;width:14px;height:14px;border-radius:4px;border:1px solid var(--line-strong);background:var(--bg2);cursor:pointer;transition:background .12s,border-color .12s,box-shadow .12s}
 input[type=checkbox]:checked,input[type=checkbox]:indeterminate{background:var(--accent);border-color:var(--accent)}
 input[type=checkbox]:disabled,input[type=radio]:disabled{cursor:not-allowed}
 input[type=checkbox]::after,input[type=radio]::after{content:'';position:absolute;opacity:0;transition:opacity .1s,transform .12s ease-out}
 input[type=checkbox]::after{left:4px;top:1.5px;width:4px;height:7px;border:2px solid var(--accent-ink);border-top:0;border-left:0;transform:rotate(45deg) scale(.7)}
 input[type=checkbox]:checked::after{opacity:1;transform:rotate(45deg) scale(1)}
}`;
const bundle = await build({
    stdin: { contents: `import {register} from ${JSON.stringify(fileURLToPath(new URL('../web/plugin.jsx', import.meta.url)))};register();`, resolveDir: packageRoot },
    bundle: true, write: false, format: 'esm', loader: { '.css': 'text' }, define: { 'process.env.NODE_ENV': '"production"' },
    plugins: [{ name: 'host-fixture', setup(build) {
        build.onResolve({ filter: /^@oie\/web-shell$/ }, () => ({ path: 'host', namespace: 'fixture' }));
        build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: mock, loader: 'js', resolveDir: packageRoot }));
    } }],
});
const server = createServer((req, res) => {
    res.setHeader('Content-Type', req.url === '/main.js' ? 'application/javascript' : 'text/html');
    res.end(req.url === '/main.js' ? bundle.outputFiles[0].text : `<!doctype html><html><head><style>${css}</style></head><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>`);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
const errors = [];

async function checkboxVisual(control, checked) {
    await expect(control).toBeChecked({ checked });
    // Poll the actual transition endpoints rather than hiding animation bugs.
    await expect.poll(() => control.evaluate(input => {
        const style = getComputedStyle(input), glyph = getComputedStyle(input, '::after');
        const token = name => { const probe = document.createElement('span'); probe.style.color = `var(${name})`; input.parentElement.append(probe); const color = getComputedStyle(probe).color; probe.remove(); return color; };
        return { background: style.backgroundColor, border: style.borderTopColor, glyph: glyph.borderRightColor,
            opacity: glyph.opacity, padding: style.padding, width: style.width, height: style.height,
            expectedBackground: token(input.checked ? '--accent' : '--bg2'), expectedInk: token('--accent-ink') };
    })).toMatchObject({ opacity: checked ? '1' : '0', padding: '0px', width: '14px', height: '14px' });
    await expect.poll(() => control.evaluate(input => {
        const style = getComputedStyle(input), glyph = getComputedStyle(input, '::after');
        const probe = document.createElement('span'); probe.style.color = `var(${input.checked ? '--accent' : '--bg2'})`; input.parentElement.append(probe);
        const background = getComputedStyle(probe).color; probe.style.color = 'var(--accent-ink)'; const ink = getComputedStyle(probe).color; probe.remove();
        return style.backgroundColor === background && (!input.checked || (style.borderTopColor === background && glyph.borderRightColor === ink && background !== ink));
    })).toBe(true);
}

async function summary(page) {
    return page.locator('.dbg-settings-grid > div').evaluateAll(items => Object.fromEntries(items.map(item => [item.querySelector('dt').textContent, item.querySelector('dd').textContent])));
}

try {
    browser = await chromium.launch({ headless: true });
    const readOnly = await browser.newPage();
    readOnly.on('pageerror', error => errors.push(error.message));
    await readOnly.goto(`http://127.0.0.1:${server.address().port}/?mode=view-only`);
    await expect(readOnly.getByText('Your account can read deployed sources.', { exact: false })).toBeVisible();
    const channel = readOnly.getByRole('combobox', { name: 'Channel', exact: true });
    await expect(channel).toBeEnabled();
    await channel.selectOption('channel-1');
    await expect(readOnly.locator('.dbg-editor textarea')).toHaveValue('value;');
    await expect(readOnly.getByRole('button', { name: 'Arm debugger', exact: true })).toBeDisabled();
    await expect(readOnly.getByRole('button', { name: 'Pause next line', exact: true })).toBeDisabled();
    await readOnly.getByLabel('Message ID (optional)', { exact: true }).press('Enter');
    assert.equal(await readOnly.evaluate(() => fixture.calls.filter(call => call[0] === 'POST').length), 0);
    await readOnly.close();
    for (const theme of ['dark', 'light']) {
        const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
        page.setDefaultTimeout(10000);
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
        const entry = page.getByRole('checkbox', { name: 'Break on entry', exact: true });
        const exceptions = page.getByRole('checkbox', { name: 'Pause on exceptions', exact: true });
        const lifecycle = page.getByRole('checkbox', { name: 'Include lifecycle and scripts without a message', exact: true });
        const evaluation = page.getByRole('checkbox', { name: 'Enable evaluation and value editing', exact: true });
        await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
        for (const [control, initial] of [[entry, true], [exceptions, false]]) {
            await checkboxVisual(control, initial);
            await control.click(); await checkboxVisual(control, !initial);
            const label = control.locator('..'), box = await label.boundingBox();
            await label.click({ position: { x: box.width - 5, y: box.height / 2 } }); await checkboxVisual(control, initial);
            await control.focus(); await page.keyboard.press('Space'); await checkboxVisual(control, !initial);
        }
        await expect(lifecycle).toBeEnabled(); await lifecycle.check(); await checkboxVisual(lifecycle, true);
        await expect(evaluation).toBeDisabled();
        await expect(evaluation.locator('..')).toHaveAttribute('title', 'Evaluation is not available for your account.');
        await expect(evaluation).toHaveAccessibleDescription('Evaluation is not available for your account.');
        await page.getByLabel('Connector ID (optional)', { exact: true }).fill('0');
        await page.getByLabel('Maximum pause (seconds)', { exact: true }).fill('45');
        await page.getByText('Limit the script surface', { exact: true }).click();
        await page.getByLabel('Script kind', { exact: true }).fill('transformer');
        await page.getByLabel('Exact deployed source').selectOption('source-1');
        await checkboxVisual(entry, false); await checkboxVisual(exceptions, true);
        await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
        await expect(page.getByRole('heading', { name: 'Session settings', exact: true })).toBeVisible();
        assert.equal(await page.locator('.dbg-target').count(), 0, 'armed view must not show frozen options');
        const requests = await page.evaluate(() => fixture.calls.filter(call => call[0] === 'POST' && call[1].endsWith('/sessions')));
        assert.equal(requests.length, 1);
        const { tabId, requestId, requestExpiresAt, ...options } = requests[0][2];
        assert.equal(typeof tabId, 'string');
        assert.equal(typeof requestId, 'string');
        assert.equal(requestExpiresAt, 30000);
        assert.deepEqual(options, { channelId: 'channel-1', connectorId: 0, scriptKind: 'transformer', sourceId: 'source-1', breakOnEntry: false, pauseOnExceptions: true, pauseOnDebugger: true, includeLifecycle: true, includeGlobal: false, evaluationEnabled: false, watches: [], maxPauseMillis: 45000, leaseMillis: 15000 });
        const expected = { Channel: 'Options fixture', 'Message filter': 'Any message', 'Connector filter': 'Source (0)', 'Script kind': 'transformer', 'Deployed source': 'channel-1_transformer_0', 'Break on entry': 'Off', 'Pause on exceptions': 'On', 'Lifecycle / scripts without a message': 'On', Evaluation: 'Off', 'Maximum pause': '45 seconds' };
        assert.deepEqual(await summary(page), expected);
        await page.evaluate(() => fixture.execution());
        await expect(page.locator('.dbg-status')).toHaveText('running');
        assert.deepEqual(await summary(page), expected, 'worker message/connector identity cannot replace original target filters');

        await page.getByRole('button', { name: 'End session', exact: true }).click();
        await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
        await checkboxVisual(entry, false); await checkboxVisual(exceptions, true); await checkboxVisual(lifecycle, true);
        await expect(page.getByLabel('Connector ID (optional)', { exact: true })).toHaveValue('0');
        await expect(page.getByLabel('Maximum pause (seconds)', { exact: true })).toHaveValue('45');
        await expect(page.getByLabel('Script kind', { exact: true })).toHaveValue('transformer');
        await expect(page.getByLabel('Exact deployed source')).toHaveValue('source-1');
        assert.equal(await page.locator('.dbg-session-settings').count(), 0);

        await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
        await expect(page.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
        await page.evaluate(() => fixture.close());
        await expect(page.getByRole('heading', { name: 'Ended session settings', exact: true })).toBeVisible();
        assert.equal(await page.locator('.dbg-target').count(), 0, 'closed session retains settings summary until cleared');
        assert.deepEqual(await summary(page), expected);
        await page.evaluate(() => fixture.evict());
        await page.getByRole('button', { name: 'Clear session', exact: true }).click();
        await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
        await checkboxVisual(entry, false); await checkboxVisual(exceptions, true); await checkboxVisual(lifecycle, true);
        await expect(page.getByLabel('Maximum pause (seconds)', { exact: true })).toHaveValue('45');
        assert.equal(await page.evaluate(() => fixture.calls.filter(call => call[0] === 'DELETE').length), 1, 'confirmed closed session must clear locally after server eviction');
        await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
        await expect(page.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
        await page.getByRole('button', { name: 'End session', exact: true }).click();
        await page.close();
    }
    const page = await browser.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/?mode=engine-disabled`);
    const evaluation = page.getByRole('checkbox', { name: 'Enable evaluation and value editing', exact: true });
    await expect(evaluation).toBeDisabled();
    await expect(evaluation.locator('..')).toHaveAttribute('title', 'Evaluation is disabled in the engine settings.');
    await expect(evaluation).toHaveAccessibleDescription('Evaluation is disabled in the engine settings.');
    await page.close();
    assert.deepEqual(errors, []);
    console.log('Options browser regression passed: view-only source browsing without arming; layered host checkbox colors in both themes; mouse, label and keyboard changes; permission help; exact arm payload and retained settings across polls, End, evicted Clear and rearm.');
} finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
}
