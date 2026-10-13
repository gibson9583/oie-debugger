// Production JSX and client against an in-process host; no engine or database.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const mock = `
import React from 'react';
import {createRoot} from 'react-dom/client';
const query = new URLSearchParams(location.search), initialDelay = query.get('delay');
let session = null, serial = 0, target = {channelId:'channel-1'}, user = {id:'user-1'}, root;
const calls = [], listeners = [], pending = [];
const source = (id, generation = 1) => ({id, name:id === 'source-1' ? 'transformer_0' : id, kind:id === 'source-2' ? 'filter' : 'transformer', hash:'hash-'+id+'-'+generation, generation, text:id+' generation '+generation, lineCount:1, breakableLines:[1], authoredMappings:[]});
const sources = channel => channel === 'channel-2' ? [source('other-source')] : [source('source-1'), source('source-2')];
let holdNext = initialDelay;
const fixture = window.fixture = {
    calls, pending, hold:kind=>{holdNext=kind;},
    release:(index, fail=false)=>{const request=pending[index];if(fail)request.reject(new Error('Stale source failure'));else request.resolve(request.kind==='list'?[source('stale-source')]:source('stale-source'));},
    close:()=>{session={...session,status:'closed',closeReason:'maximum pause expired'};},
    pause:()=>{session={...session,status:'paused',pauseGeneration:1,frames:[{index:0,sourceId:'source-1',sourceHash:'hash-source-1-2',sourceGeneration:2,line:1}]};},
    logout:()=>{user={id:'user-2'};listeners.forEach(fn=>fn());}, unmount:()=>root.unmount()
};
async function response(kind, value, params) {
    if (holdNext !== kind) return JSON.stringify(value);
    holdNext = null;
    return JSON.stringify(await new Promise((resolve,reject)=>pending.push({kind,params,resolve,reject})));
}
const api = {
    get:async(path,params)=>{
        calls.push(['GET',path,params]);
        if(path.endsWith('/capabilities'))return JSON.stringify({available:true,apiVersion: 1, commandProtocol: 2,controlAllowed:true,evaluationAllowed:false,globalAllowed:false,armRequestExpiresAt:Date.now()+60000});
        if(path.endsWith('/sources'))return response('list',sources(params.channelId),params);
        if(path.includes('/sources/'))return response('detail',source(path.split('/').pop(),params.debugSessionId && session?.status==='paused'?2:1),params);
        if(path.includes('/sessions/'))return JSON.stringify(session);
        throw new Error('Unexpected GET '+path);
    },
    post:async(path,text)=>{
        const body=JSON.parse(text);calls.push(['POST',path,body]);
        if(path.endsWith('/sessions')){
            if(query.has('reject'))throw Object.assign(new Error('Arm denied'),{status:403});
            session={id:'session-'+(++serial),channelId:body.channelId,status:'armed',pauseGeneration:0};return JSON.stringify(session);
        }
        if(path.endsWith('/heartbeat'))return '{}';
        if(path.endsWith('/commands'))return JSON.stringify({status:'completed'});
        throw new Error('Unexpected POST '+path);
    },
    del:async(path)=>{calls.push(['DELETE',path]);session=null;return '{}';},
    channels:{list:async()=>[{id:'channel-1',name:'First channel'},{id:'channel-2',name:'Second channel'}]},onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}
};
export const platform={React,api,checkTask:()=>true,oie:{uuid:()=>crypto.randomUUID()},store:{getState:key=>key==='user'?user:key==='debuggerTarget'?target:null,setState:(key,value)=>{if(key==='debuggerTarget')target=value;},subscribe:(key,fn)=>{listeners.push(fn);return()=>listeners.splice(listeners.indexOf(fn),1);}},router:{navigate:()=>{}},registerIcon:()=>{},registerNavItem:()=>{},registerChannelAction:()=>{},registerMessageAction:()=>{},reactView:component=>component,registerView:(path,View)=>{root=createRoot(document.getElementById('root'));root.render(React.createElement(View));},createCodeEditor:opts=>{const el=document.createElement('div'),area=document.createElement('textarea');area.readOnly=true;area.value=opts.value;area.setAttribute('aria-label',opts.ariaLabel);el.append(area);return{el,area,dispose:()=>{}}}};
`;
const bundle = await build({
    stdin: { contents: `import {register} from ${JSON.stringify(fileURLToPath(new URL('../web/plugin.jsx', import.meta.url)))};register();`, resolveDir: packageRoot },
    bundle: true, write: false, format: 'esm', loader: { '.css': 'text' }, define: { 'process.env.NODE_ENV': '"production"' },
    plugins: [{ name: 'host-fixture', setup(builder) {
        builder.onResolve({ filter: /^@oie\/web-shell$/ }, () => ({ path: 'host', namespace: 'fixture' }));
        builder.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: mock, loader: 'js', resolveDir: packageRoot }));
    } }],
});
const server = createServer((req, res) => {
    res.setHeader('Content-Type', req.url === '/main.js' ? 'application/javascript' : 'text/html');
    res.end(req.url === '/main.js' ? bundle.outputFiles[0].text : '<!doctype html><html><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
const errors = [];
const editor = page => page.getByLabel('Deployed JavaScript source', { exact: true });
const sourceRequests = page => page.evaluate(() => fixture.calls.filter(call => call[0] === 'GET' && call[1].includes('/sources')));
async function open(query = '') {
    const page = await browser.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/?${query}`);
    await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
    return page;
}
async function waitPending(page, count = 1) {
    await page.waitForFunction(count => fixture.pending.length === count, count);
}
async function arm(page) {
    await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
    await expect(page.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
}
async function breakpoint(page) {
    await page.getByText('Add breakpoint by line', { exact: true }).click();
    const submit = page.getByRole('button', { name: 'Set breakpoint', exact: true });
    await expect(submit).toBeEnabled();
    await submit.click();
    await expect(page.getByRole('checkbox', { name: 'Enable breakpoint at line 1' })).toBeVisible();
    const command = await page.evaluate(() => fixture.calls.find(call => call[2]?.action === 'setBreakpoints')[2]);
    assert.equal(command.sourceId, 'source-1');
    assert.equal(command.sourceHash, 'hash-source-1-1');
    assert.equal(command.sourceGeneration, 1);
}
async function releaseStale(page, index = 0, fail = false) {
    await page.evaluate(({index, fail}) => fixture.release(index, fail), {index, fail});
    // Flush the deferred request and React's subsequent render before assertions.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

try {
    browser = await chromium.launch({ headless: true });
    for (const delay of ['list', 'detail']) {
        for (const entry of [true, false]) {
            const page = await open(`delay=${delay}`);
            await waitPending(page);
            await page.getByRole('checkbox', { name: 'Break on entry', exact: true }).setChecked(entry);
            await arm(page);
            await expect(editor(page)).toHaveValue('source-1 generation 1');
            await breakpoint(page);
            await releaseStale(page);
            await expect(editor(page)).toHaveValue('source-1 generation 1');
            await expect(page.locator('#dbg-source option[value="stale-source"]')).toHaveCount(0);
            const count = (await sourceRequests(page)).length;
            await expect.poll(() => page.evaluate(() => fixture.calls.filter(call => call[0] === 'GET' && /\/sessions\//.test(call[1])).length)).toBeGreaterThan(1);
            assert.equal((await sourceRequests(page)).length, count, 'polling must not loop source loads');
            await page.close();
        }
        const denied = await open(`delay=${delay}&reject`);
        await waitPending(denied);
        await denied.getByRole('button', { name: 'Arm debugger', exact: true }).click();
        await expect(denied.getByRole('alert')).toContainText('Arm denied');
        await expect(editor(denied)).toHaveValue('source-1 generation 1');
        await releaseStale(denied, 0, true);
        await expect(denied.getByRole('alert')).toContainText('Arm denied');
        await expect(editor(denied)).toHaveValue('source-1 generation 1');
        await denied.close();

        const changed = await open(`delay=${delay}`);
        await waitPending(changed);
        await changed.getByRole('combobox', { name: 'Channel', exact: true }).selectOption('channel-2');
        await expect(editor(changed)).toHaveValue('other-source generation 1');
        await arm(changed);
        await releaseStale(changed);
        await expect(editor(changed)).toHaveValue('other-source generation 1');
        await expect(changed.getByRole('combobox', { name: 'Deployed source', exact: true })).toHaveValue('other-source');
        await changed.close();

        for (const action of ['logout', 'unmount']) {
            const invalid = await open(`delay=${delay}`);
            await waitPending(invalid);
            await invalid.evaluate(action => fixture[action](), action);
            const count = (await sourceRequests(invalid)).length;
            await releaseStale(invalid);
            await expect(editor(invalid)).toHaveCount(0);
            await expect(invalid.locator('#dbg-source option[value="stale-source"]')).toHaveCount(0);
            assert.equal((await sourceRequests(invalid)).length, count, 'invalid owner or unmount must not restart source loads');
            await invalid.close();
        }
    }
    const selection = await open('delay=detail');
    await waitPending(selection);
    await selection.getByRole('combobox', { name: 'Deployed source', exact: true }).selectOption('source-2');
    await expect(editor(selection)).toHaveValue('source-2 generation 1');
    await arm(selection);
    await releaseStale(selection);
    await expect(editor(selection)).toHaveValue('source-2 generation 1');
    await selection.close();

    for (const delay of ['list', 'detail']) {
        const lifecycle = await open();
        await expect(editor(lifecycle)).toHaveValue('source-1 generation 1');
        await arm(lifecycle);
        await expect(editor(lifecycle)).toHaveValue('source-1 generation 1');
        for (const [index, transition] of ['close', 'clear', 'rearm', 'end'].entries()) {
            await lifecycle.evaluate(kind => fixture.hold(kind), delay);
            await lifecycle.getByRole('button', { name: 'Refresh', exact: true }).click();
            await waitPending(lifecycle, index + 1);
            const requestCount = (await sourceRequests(lifecycle)).length;
            if (transition === 'close') {
                await lifecycle.evaluate(() => fixture.close());
                await expect(lifecycle.getByRole('button', { name: 'Clear session', exact: true })).toBeEnabled();
            } else if (transition === 'rearm') await arm(lifecycle);
            else {
                await lifecycle.getByRole('button', { name: transition === 'clear' ? 'Clear session' : 'End session', exact: true }).click();
                await expect(lifecycle.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
            }
            await expect(editor(lifecycle)).toHaveValue('source-1 generation 1');
            assert((await sourceRequests(lifecycle)).length > requestCount, 'session transitions must replace abandoned reads');
            await releaseStale(lifecycle, index);
            await expect(editor(lifecycle)).toHaveValue('source-1 generation 1');
            await expect(lifecycle.locator('#dbg-source option[value="stale-source"]')).toHaveCount(0);
        }
        await arm(lifecycle);
        await lifecycle.evaluate(() => fixture.pause());
        await expect(editor(lifecycle)).toHaveValue('source-1 generation 2');
        await expect(lifecycle.getByRole('alert')).toHaveCount(0);
        await lifecycle.close();
    }
    assert.deepEqual(errors, []);
    console.log('Source-loading browser regression passed: delayed list/detail across arm with either entry setting, rejection, close/clear/rearm/end, changed channel/selection, paused generation, owner change and unmount; executable breakpoint writes and stale response/error guards; no polling reload loop.');
} finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
}
