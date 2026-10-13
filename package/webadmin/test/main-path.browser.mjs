// Happy-path browser check with a mock host and local React/Playwright.
// This is a mock engine, not live engine acceptance.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const output = process.env.OIE_DEBUGGER_BROWSER_OUTPUT_DIR ? resolve(process.env.OIE_DEBUGGER_BROWSER_OUTPUT_DIR)
    : fileURLToPath(new URL('../../../test-results/browser/main-path/', import.meta.url));
await mkdir(output, { recursive: true });
const mock = `
import React from 'react';
import {createRoot} from 'react-dom/client';
let target, session, stage=0; const calls=[]; window.debuggerCalls=calls;
const source={id:'source-1',name:'ADT source transformer',hash:'527f9abc12340000',generation:1,lineCount:8,breakableLines:[2,3,4,5,6],kind:'transformer',text:'function doTransform() {\\n    var patientId = msg["PID"]["PID.3"].toString();\\n    channelMap.put("patientId", patientId);\\n    var routed = true;\\n    logger.info("Ready for routing");\\n    return routed;\\n}\\ndoTransform();',authoredMappings:[{authoredId:'transformer-1',name:'Set patient ID',kind:'transformer',revision:4,generatedStartLine:2,generatedEndLine:3}]};
const snapshot=()=>({...session,frames:stage===0?[]:[{index:0,functionName:'doTransform',sourceId:source.id,sourceHash:source.hash,sourceGeneration:1,line:stage>1?4:3}],inspection:{locals:{type:'object',properties:{patientId:{type:'string',value:'DEMO-123'}}},maps:{channelMap:{patientId:'DEMO-123'}}},watches:[]});
const api={get:async(path,params)=>{calls.push(['GET',path,params]);if(path.endsWith('/capabilities'))return JSON.stringify({available:true,apiVersion: 1, commandProtocol: 2,armRequestExpiresAt:30000,evaluationAllowed:true,globalAllowed:true});if(path.endsWith('/sources'))return JSON.stringify([source]);if(path.includes('/sources/'))return JSON.stringify(source);if(path.includes('/sessions/')){session.status='paused';const result=snapshot();stage++;return JSON.stringify(result)}},post:async(path,body)=>{const data=JSON.parse(body);calls.push(['POST',path,data]);if(path.endsWith('/sessions')){session={id:'session-1',channelId:'channel-1',status:'armed',pauseGeneration:1,reason:'entry'};return JSON.stringify(session)}if(path.endsWith('/commands')){if(data.action==='stepOver'){session.pauseGeneration++;session.reason='step';}if(data.action==='continue')session.status='running';return JSON.stringify({commandId:data.commandId,status:'completed',result:data.action==='inspect'?snapshot().inspection:{ok:true}})}return '{}'},put:async(path,body)=>{calls.push(['PUT',path,JSON.parse(body)]);return '{}'},del:async(path)=>{calls.push(['DELETE',path]);return '{}'},channels:{list:async()=>[{id:'channel-1',name:'ADT Inbound (demo fixture)'}]},onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}};
export const platform={React,api,oie:{uuid:()=>crypto.randomUUID()},store:{getState:key=>key==='user'?{id:'demo'}:key==='debuggerTarget'?target:null,setState:(key,value)=>{if(key==='debuggerTarget')target=value},subscribe:()=>()=>{}},router:{navigate:()=>{}},registerIcon:()=>{},registerNavItem:()=>{},registerChannelAction:()=>{},registerMessageAction:()=>{},reactView:component=>component,registerView:(path,View)=>createRoot(document.getElementById('root')).render(React.createElement(View)),createCodeEditor:options=>{const el=document.createElement('div');el.className='ce';const area=document.createElement('textarea');area.className='ce-area';area.style.cssText='height:100%;width:100%;resize:none;padding:12px;white-space:pre;tab-size:4';area.readOnly=options.readOnly;area.value=options.value;area.setAttribute('aria-label',options.ariaLabel);el.append(area);return {el,area,dispose:()=>{}}}};
`;
const bundle = await build({
    stdin: { contents: `import {register} from ${JSON.stringify(fileURLToPath(new URL('../web/plugin.js', import.meta.url)))}; register();`, resolveDir: packageRoot },
    bundle: true, write: false, format: 'esm', define: { 'process.env.NODE_ENV': '"production"' },
    plugins: [{ name: 'host-fixture', setup(build) {
        build.onResolve({ filter: /^@oie\/web-shell$/ }, () => ({ path: 'host', namespace: 'fixture' }));
        build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: mock, loader: 'js', resolveDir: packageRoot }));
    } }],
});
const css = `:root{--font-ui:system-ui;--font-mono:ui-monospace,monospace;--text:#d9e2ea;--text-dim:#8a99a8;--bg0:#0c1116;--bg1:#111922;--bg2:#16212c;--bg3:#1c2a38;--line:#233140;--line-strong:#2f4254;--accent:#58a6e8;--accent-press:#4690d1;--accent-ink:#06141f;--accent-glow:rgba(88,166,232,.16);--warn:#f0b541;--err:#ff5f5f;--ok:#3ddc84;color-scheme:dark}body{margin:0;background:var(--bg1)}`;
const server = createServer((req, res) => {
    if (req.url === '/main.js') { res.setHeader('Content-Type', 'application/javascript'); res.end(bundle.outputFiles[0].text); }
    else { res.setHeader('Content-Type', 'text/html'); res.end(`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Debugger · mock main-path verification</title><style>${css}</style></head><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>`); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = []; page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.locator('.dbg-fields select').first().selectOption('channel-1');
    await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
    await page.getByRole('button', { name: 'doTransform' }).waitFor();
    await page.getByLabel('Deployed JavaScript source').waitFor();
    await page.getByText('Add breakpoint by line', { exact: true }).click();
    await page.getByLabel('Line', { exact: true }).fill('4');
    await page.getByRole('button', { name: 'Set breakpoint', exact: true }).click();
    await page.getByRole('button', { name: 'Remove breakpoint at line 4' }).waitFor();
    await page.getByRole('button', { name: 'Step over', exact: true }).click();
    await page.locator('.dbg-execution-title').filter({ hasText: 'Paused after step' }).waitFor();
    await page.locator('.dbg-execution').filter({ hasText: 'line 4' }).waitFor();
    assert.equal(await page.getByText('"DEMO-123"', { exact: true }).isVisible(), true, 'locals render as readable values');
    assert.equal(await page.locator('.dbg-diagnostics').evaluate(el => el.open), false, 'raw diagnostics stay collapsed');
    assert.equal(await page.locator('pre:visible').count(), 0, 'inspection receipts are not rendered twice as JSON');
    assert.equal(await page.getByText('Evaluate in selected frame', { exact: true }).count(), 0, 'disabled evaluation does not expose inactive forms');
    await page.screenshot({ path: output + '/desktop.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: output + '/mobile.png', fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false, 'mobile horizontal overflow');
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await page.getByRole('button', { name: 'End session', exact: true }).click();
    await page.getByRole('button', { name: 'Arm debugger', exact: true }).waitFor();
    const calls = await page.evaluate(() => window.debuggerCalls);
    assert(calls.some(call => call[0] === 'POST' && call[2].action === 'setBreakpoints' && call[2].sourceHash === '527f9abc12340000' && call[2].commandId));
    assert(calls.some(call => call[0] === 'POST' && call[2].action === 'stepOver'));
    assert(calls.some(call => call[0] === 'DELETE'));
    assert.deepEqual(errors, []);
    await writeFile(output + '/result.json', JSON.stringify({ passed: true, kind: 'mock-host-main-path', checks: ['arm', 'delayed frames', 'fallback editor', 'verified breakpoint', 'step', 'continue', 'end', 'desktop/mobile layout'], browserErrors: errors, calls }, null, 2));
    console.log('Mock host main path passed; desktop/mobile screenshots: ' + output);
} finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
}
