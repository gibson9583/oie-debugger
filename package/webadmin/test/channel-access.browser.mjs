// Production JSX against an in-process RBAC-filtered host; no live engine or database.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const mock = `
import React from 'react';
import {createRoot} from 'react-dom/client';
const query = new URLSearchParams(location.search);
let user = {id:'user-1'}, target = query.get('initial') ? {channelId:query.get('initial'),...(query.has('message')?{messageId:'17'}:{})} : null;
let root, session = null, serial = 0, globalAllowed = query.has('global'), failCapabilities = query.has('capability-failure');
let capabilityMode = query.get('capabilities') || '';
let loseFirstArm = query.has('lost-arm');
const calls = [], pending = [], sends = [], deletes = [], receipts = new Map(), subscriptions = new Map();
const notify = key => subscriptions.get(key)?.forEach(listener => listener());
const source = channel => ({id:'source-'+channel,name:channel+'_transformer_0',kind:'transformer',
    hash:'hash-'+channel,generation:1,text:channel+' source',lineCount:1,breakableLines:[1],authoredMappings:[]});
const fixture = window.fixture = {
    calls, pending, sends, deletes,
    capabilities:({global,fail,mode})=>{if(global!==undefined)globalAllowed=global;if(fail!==undefined)failCapabilities=fail;if(mode!==undefined)capabilityMode=mode;},
    completeSend:(index,fail=false)=>{if(fail)sends[index].reject(new Error('Send response lost'));else sends[index].resolve(71);},
    completeDelete:index=>deletes[index](),
    complete:(index, ids, fail=false)=>{
        const request = pending[index];
        if (!request || request.done) throw new Error('Invalid channel-list completion '+index);
        request.done = true;
        if (fail) request.reject(new Error('Channel lookup failed'));
        else request.resolve(ids.map(id=>({id,name:id==='channel-a'?'Allowed channel':'Restricted channel'})));
    },
    target:channelId=>{target={channelId};notify('debuggerTarget');},
    logout:()=>{user={id:'user-2'};notify('user');},
    unmount:()=>root.unmount(),
    focus:()=>window.dispatchEvent(new Event('focus'))
};
const api = {
    get:async(path,params)=>{
        calls.push(['GET',path,params]);
        if(path.endsWith('/capabilities')){
            if(failCapabilities)throw new Error('Capability lookup failed');
            if(capabilityMode==='null')return 'null';
            if(capabilityMode==='empty')return '';
            if(capabilityMode==='reject-null')throw null;
            if(capabilityMode==='reject-false')throw false;
            return JSON.stringify({available:true,apiVersion:1,commandProtocol:2,
                controlAllowed:true,evaluationAllowed:false,globalAllowed,armRequestExpiresAt:Date.now()+60000});
        }
        if(path.endsWith('/sources'))return JSON.stringify([source(params.channelId)]);
        if(path.includes('/sources/'))return JSON.stringify(source(params.channelId));
        if(path.includes('/sessions/'))return JSON.stringify(session);
        throw new Error('Unexpected GET '+path);
    },
    post:async(path,text)=>{
        const body=JSON.parse(text);calls.push(['POST',path,body]);
        if(path.endsWith('/sessions')){
            if(!receipts.has(body.requestId)){
                session={id:'session-'+(++serial),channelId:body.channelId,status:'armed',pauseGeneration:0};
                receipts.set(body.requestId,session);
            }
            if(loseFirstArm){loseFirstArm=false;throw new Error('Arm response lost');}
            session=receipts.get(body.requestId);
            return JSON.stringify(session);
        }
        if(path.endsWith('/heartbeat'))return JSON.stringify(session);
        throw new Error('Unexpected POST '+path);
    },
    del:async(path)=>{calls.push(['DELETE',path]);if(query.has('defer-delete'))await new Promise(resolve=>deletes.push(resolve));session=null;return '{}';},
    channels:{list:()=>new Promise((resolve,reject)=>{calls.push(['CHANNELS',pending.length]);pending.push({resolve,reject});})},
    messages:{processNew:(...args)=>{calls.push(['MESSAGE',...args]);return new Promise((resolve,reject)=>sends.push({resolve,reject}));}},
    onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}
};
const asyncGet=api.get;
api.get=(path,params)=>{
    if(path.endsWith('/capabilities')&&capabilityMode==='sync'){
        calls.push(['GET',path,params]);throw new Error('Synchronous capability failure');
    }
    return asyncGet(path,params);
};
export const platform={React,api,checkTask:()=>true,oie:{uuid:()=>crypto.randomUUID()},
    store:{getState:key=>key==='user'?user:key==='debuggerTarget'?target:null,
        setState:(key,value)=>{if(key==='debuggerTarget')target=value;notify(key);},
        subscribe:(key,listener)=>{if(!subscriptions.has(key))subscriptions.set(key,new Set());
            subscriptions.get(key).add(listener);return()=>subscriptions.get(key).delete(listener);}},
    router:{navigate:()=>{}},registerIcon:()=>{},registerNavItem:()=>{},registerChannelAction:()=>{},registerMessageAction:()=>{},
    reactView:component=>component,
    registerView:(path,View)=>{root=createRoot(document.getElementById('root'));root.render(React.createElement(View));},
    createCodeEditor:opts=>{const el=document.createElement('div'),area=document.createElement('textarea');
        area.readOnly=true;area.value=opts.value;area.setAttribute('aria-label',opts.ariaLabel);el.append(area);
        return{el,area,dispose:()=>{}}}
};
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
    res.end(req.url === '/main.js' ? bundle.outputFiles[0].text : `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
        :root{--font-ui:system-ui;--font-mono:monospace;--text:#d9e2ea;--text-dim:#8a99a8;--bg1:#111922;--bg2:#16212c;--bg3:#1c2a38;--line:#233140;--line-strong:#2f4254;--accent:#58a6e8;--accent-ink:#06141f;--warn:#f0b541;--err:#ff5f5f;--ok:#3ddc84;color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:var(--bg1)}
        </style></head><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>`);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
const errors = [];
const channel = page => page.getByRole('combobox', { name: 'Channel', exact: true });
const arm = page => page.getByRole('button', { name: 'Arm debugger', exact: true });
const editor = page => page.getByLabel('Deployed JavaScript source', { exact: true });
const sourceRequests = page => page.evaluate(() => fixture.calls.filter(call => call[0] === 'GET' && call[1].includes('/sources')));
const armRequests = page => page.evaluate(() => fixture.calls.filter(call => call[0] === 'POST' && call[1].endsWith('/sessions')));
async function settled(page) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
async function waitPending(page, count) {
    await expect.poll(() => page.evaluate(() => fixture.pending.length)).toBe(count);
}
async function open(query = '') {
    const page = await browser.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/?${query}`);
    await waitPending(page, 1);
    await settled(page);
    return page;
}
async function complete(page, index, ids, fail = false) {
    await page.evaluate(({ index, ids, fail }) => fixture.complete(index, ids, fail), { index, ids, fail });
    await settled(page);
}
async function focus(page, count) {
    await page.evaluate(() => fixture.focus());
    await waitPending(page, count);
    await settled(page);
}
async function unusedForbiddenTarget(page) {
    assert.equal((await sourceRequests(page)).some(call => call[2]?.channelId === 'channel-b'), false,
        'A forbidden target must never trigger a source-list or source-detail request');
    assert.equal((await armRequests(page)).some(call => call[2]?.channelId === 'channel-b'), false,
        'A forbidden target must never reach Arm');
}
async function submitTargetForm(page) {
    // Exercise the handler directly too: disabled controls alone cannot validate stale state.
    await channel(page).evaluate(select => select.closest('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    await settled(page);
}

try {
    browser = await chromium.launch({ headless: true });

    const stored = await open('initial=channel-b');
    await expect(channel(stored).locator('option[value="channel-b"]')).toHaveCount(0);
    await expect(arm(stored)).toBeDisabled();
    await submitTargetForm(stored);
    assert.equal((await sourceRequests(stored)).length, 0, 'Do not read stored targets before the authorized list arrives');
    assert.equal((await armRequests(stored)).length, 0);
    await complete(stored, 0, ['channel-a']);
    await expect(channel(stored)).toHaveValue('');
    await expect(channel(stored).locator('option[value="channel-b"]')).toHaveCount(0);
    await expect(channel(stored).locator('option[value="global"]')).toHaveCount(0);
    await expect(arm(stored)).toBeDisabled();
    await stored.evaluate(() => fixture.target('channel-b'));
    await settled(stored);
    await expect(channel(stored)).toHaveValue('');
    await expect(channel(stored).locator('option[value="channel-b"]')).toHaveCount(0);
    await submitTargetForm(stored);
    await unusedForbiddenTarget(stored);
    await channel(stored).selectOption('channel-a');
    await expect(editor(stored)).toHaveValue('channel-a source');
    if (process.env.DEBUGGER_VISUAL_OUTPUT) {
        await mkdir(process.env.DEBUGGER_VISUAL_OUTPUT, { recursive: true });
        for (const [name, viewport] of [['desktop', { width: 1360, height: 1000 }], ['mobile', { width: 390, height: 844 }]]) {
            await stored.setViewportSize(viewport);
            await stored.screenshot({ path: join(process.env.DEBUGGER_VISUAL_OUTPUT, `channel-picker-${name}.png`), fullPage: true });
        }
    }
    await stored.evaluate(() => fixture.target('channel-b'));
    await settled(stored);
    await expect(channel(stored)).toHaveValue('channel-a');
    await expect(arm(stored)).toBeEnabled();
    await arm(stored).click();
    await expect(stored.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
    assert.deepEqual((await armRequests(stored)).map(call => call[2].channelId), ['channel-a']);
    await unusedForbiddenTarget(stored);
    await stored.close();

    const failed = await open('initial=channel-a');
    await expect(arm(failed)).toBeDisabled();
    await submitTargetForm(failed);
    assert.equal((await sourceRequests(failed)).length, 0);
    await complete(failed, 0, [], true);
    await expect(failed.getByRole('alert')).toContainText('Cannot load channels');
    await expect(channel(failed)).toHaveValue('');
    await expect(channel(failed).locator('option[value="channel-a"]')).toHaveCount(0);
    await expect(arm(failed)).toBeDisabled();
    await submitTargetForm(failed);
    assert.equal((await armRequests(failed)).length, 0);
    await focus(failed, 2);
    await expect(arm(failed)).toBeDisabled();
    await complete(failed, 1, ['channel-a']);
    await expect(channel(failed).locator('option[value="channel-a"]')).toHaveCount(1);
    await channel(failed).selectOption('channel-a');
    await expect(editor(failed)).toHaveValue('channel-a source');
    await focus(failed, 3);
    await expect(arm(failed)).toBeDisabled();
    await complete(failed, 2, [], true);
    await expect(channel(failed)).toHaveValue('');
    await expect(channel(failed).locator('option[value="channel-a"]')).toHaveCount(0);
    await expect(editor(failed)).toHaveCount(0);
    await expect(arm(failed)).toBeDisabled();
    await submitTargetForm(failed);
    assert.equal((await armRequests(failed)).length, 0, 'A failed refresh cannot retain an actionable stale target');
    await failed.close();

    const capabilities = await open('initial=channel-a&capability-failure');
    await complete(capabilities, 0, ['channel-a']);
    await expect(capabilities.getByRole('alert')).toContainText('Capability lookup failed');
    await expect(channel(capabilities)).toHaveValue('');
    await expect(channel(capabilities).locator('option[value="channel-a"]')).toHaveCount(0);
    await expect(arm(capabilities)).toBeDisabled();
    await submitTargetForm(capabilities);
    assert.equal((await sourceRequests(capabilities)).length, 0);
    assert.equal((await armRequests(capabilities)).length, 0);
    await capabilities.evaluate(() => fixture.capabilities({ fail: false }));
    await focus(capabilities, 2);
    await complete(capabilities, 1, ['channel-a']);
    await expect(channel(capabilities)).toHaveValue('channel-a');
    await expect(editor(capabilities)).toHaveValue('channel-a source');
    await expect(arm(capabilities)).toBeEnabled();
    await capabilities.close();

    for (const mode of ['null', 'empty', 'sync', 'reject-null', 'reject-false']) {
        const invalid = await open(`initial=channel-a&capabilities=${mode}`);
        await complete(invalid, 0, ['channel-a']);
        await expect(invalid.getByRole('alert')).toContainText('Cannot load channels');
        await expect(channel(invalid)).toHaveValue('');
        await expect(channel(invalid).locator('option[value="channel-a"]')).toHaveCount(0);
        await expect(arm(invalid)).toBeDisabled();
        await submitTargetForm(invalid);
        assert.equal((await sourceRequests(invalid)).length, 0);
        assert.equal((await armRequests(invalid)).length, 0);
        await invalid.evaluate(() => fixture.capabilities({ mode: '' }));
        await focus(invalid, 2);
        await complete(invalid, 1, ['channel-a']);
        await expect(channel(invalid)).toHaveValue('channel-a');
        await expect(editor(invalid)).toHaveValue('channel-a source');
        await expect(arm(invalid)).toBeEnabled();
        await invalid.close();
    }

    const revoked = await open('initial=channel-b');
    await complete(revoked, 0, ['channel-a', 'channel-b']);
    await expect(channel(revoked)).toHaveValue('channel-b');
    await expect(editor(revoked)).toHaveValue('channel-b source');
    await focus(revoked, 2);
    await expect(arm(revoked)).toBeDisabled();
    const before = (await sourceRequests(revoked)).length;
    await submitTargetForm(revoked);
    assert.equal((await armRequests(revoked)).length, 0, 'A pending channel refresh must block Arm');
    await focus(revoked, 3);
    await complete(revoked, 2, ['channel-a']);
    await expect(channel(revoked)).toHaveValue('');
    await expect(channel(revoked).locator('option[value="channel-b"]')).toHaveCount(0);
    await expect(editor(revoked)).toHaveCount(0);
    await expect(arm(revoked)).toBeDisabled();
    await complete(revoked, 1, ['channel-a', 'channel-b']);
    await expect(channel(revoked).locator('option[value="channel-b"]')).toHaveCount(0);
    await expect(channel(revoked)).toHaveValue('');
    await expect(editor(revoked)).toHaveCount(0);
    assert.equal((await sourceRequests(revoked)).length, before, 'An older refresh cannot restart revoked source reads');
    await revoked.close();

    const filtered = await open('initial=channel-a');
    await complete(filtered, 0, ['channel-a']);
    await expect(editor(filtered)).toHaveValue('channel-a source');
    await filtered.getByText('Limit the script surface', { exact: true }).click();
    const exactSource = filtered.getByLabel('Exact deployed source');
    await exactSource.selectOption('source-channel-a');
    await focus(filtered, 2);
    await expect(arm(filtered)).toBeDisabled();
    await complete(filtered, 1, ['channel-a']);
    await expect(exactSource).toHaveValue('source-channel-a');
    await arm(filtered).click();
    await expect(filtered.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
    assert.equal((await armRequests(filtered))[0][2].sourceId, 'source-channel-a',
        'A permissions refresh must not broaden an exact-source Arm into any source');
    await filtered.close();

    for (const lostArm of [false, true]) {
        const existing = await open(`initial=channel-a${lostArm ? '&lost-arm' : ''}`);
        await complete(existing, 0, ['channel-a']);
        await expect(arm(existing)).toBeEnabled();
        await arm(existing).click();
        const recovery = existing.getByRole('button', { name: 'Check arm result', exact: true });
        const end = existing.getByRole('button', { name: 'End session', exact: true });
        await expect(lostArm ? recovery : end).toBeEnabled();
        await focus(existing, 2);
        await expect(lostArm ? recovery : end).toBeEnabled();
        await complete(existing, 1, [], true);
        await expect(lostArm ? recovery : end).toBeEnabled();
        if (lostArm) {
            await recovery.click();
            await expect(end).toBeEnabled();
            const requests = await armRequests(existing);
            assert.equal(requests.length, 2);
            assert.deepEqual(requests[1][2], requests[0][2], 'Discovery failure must not replace the original Arm recovery request');
        }
        await expect(editor(existing)).toHaveValue('channel-a source');
        await end.click();
        await expect(arm(existing)).toBeDisabled();
        assert.equal(await existing.evaluate(() => fixture.calls.filter(call => call[0] === 'DELETE').length), 1);
        await existing.close();
    }

    for (const sendFails of [false, true]) {
        const sending = await open('initial=channel-a');
        await complete(sending, 0, ['channel-a']);
        await expect(arm(sending)).toBeEnabled();
        await arm(sending).click();
        await expect(sending.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
        const message = sending.getByRole('textbox', { name: 'Message', exact: true });
        await message.fill('message retained during access refresh');
        await sending.getByRole('button', { name: 'Send message', exact: true }).click();
        await expect.poll(() => sending.evaluate(() => fixture.sends.length)).toBe(1);
        await focus(sending, 2);
        await complete(sending, 1, [], true);
        await sending.evaluate(fail => fixture.completeSend(0, fail), sendFails);
        await expect(sending.getByText(sendFails ? 'Send outcome unknown:' : 'Message 71 accepted by the engine.', { exact: !sendFails })).toBeVisible();
        await expect(message).toHaveValue('message retained during access refresh');
        await expect(sending.getByRole('button', { name: 'Send message', exact: true })).toBeDisabled();
        await message.evaluate(input => input.closest('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
        await settled(sending);
        assert.equal(await sending.evaluate(() => fixture.sends.length), 1, 'Refreshing access must never retry a message or allow another send');
        await sending.getByRole('button', { name: 'End session', exact: true }).click();
        await sending.close();
    }

    const rearm = await open('initial=channel-a&message&defer-delete');
    await complete(rearm, 0, ['channel-a']);
    await expect(arm(rearm)).toBeEnabled();
    await arm(rearm).click();
    await expect(rearm.getByRole('button', { name: 'Arm for a new message', exact: true })).toBeEnabled();
    await rearm.getByRole('button', { name: 'Arm for a new message', exact: true }).click();
    await expect.poll(() => rearm.evaluate(() => fixture.deletes.length)).toBe(1);
    await focus(rearm, 2);
    await complete(rearm, 1, ['channel-b']);
    await rearm.evaluate(() => fixture.completeDelete(0));
    await expect(channel(rearm)).toHaveValue('');
    await expect(arm(rearm)).toBeDisabled();
    await settled(rearm);
    assert.equal((await armRequests(rearm)).length, 1, 'Revocation during DELETE must prevent the replacement Arm');
    await rearm.close();

    for (const action of ['logout', 'unmount']) {
        for (const fail of [false, true]) {
            const invalid = await open('initial=channel-b');
            await invalid.evaluate(action => fixture[action](), action);
            await settled(invalid);
            const count = (await sourceRequests(invalid)).length;
            await complete(invalid, 0, ['channel-a', 'channel-b'], fail);
            await expect(invalid.locator('option[value="channel-b"]')).toHaveCount(0);
            await expect(editor(invalid)).toHaveCount(0);
            assert.equal((await sourceRequests(invalid)).length, count, 'Invalid owner or unmount must fence pending channel reads');
            assert.equal((await armRequests(invalid)).length, 0);
            if (action === 'logout') {
                await expect(invalid.getByRole('alert')).toContainText('login or engine changed');
                await expect(invalid.getByRole('alert')).not.toContainText('Channel lookup failed');
            }
            await invalid.close();
        }
    }

    const globalDenied = await open('initial=global');
    await complete(globalDenied, 0, ['channel-a']);
    await expect(channel(globalDenied).locator('option[value="global"]')).toHaveCount(0);
    await expect(channel(globalDenied)).toHaveValue('');
    await expect(arm(globalDenied)).toBeDisabled();
    assert.equal((await sourceRequests(globalDenied)).length, 0);
    await globalDenied.close();
    const globalAllowed = await open('global');
    await complete(globalAllowed, 0, ['channel-a']);
    await expect(channel(globalAllowed).locator('option[value="global"]')).toHaveCount(1);
    await channel(globalAllowed).selectOption('global');
    await expect(editor(globalAllowed)).toHaveValue('global source');
    await globalAllowed.evaluate(() => fixture.capabilities({ global: false }));
    await focus(globalAllowed, 2);
    await complete(globalAllowed, 1, ['channel-a']);
    await expect(channel(globalAllowed).locator('option[value="global"]')).toHaveCount(0);
    await expect(channel(globalAllowed)).toHaveValue('');
    await expect(editor(globalAllowed)).toHaveCount(0);
    await expect(arm(globalAllowed)).toBeDisabled();
    await submitTargetForm(globalAllowed);
    assert.equal((await armRequests(globalAllowed)).length, 0);
    await globalAllowed.close();

    assert.deepEqual(errors, []);
    console.log('Channel-access browser regression passed: filtered targets; pending/failed lists and capabilities; focus revocation and overlapping responses; owner/unmount fences; global permission changes; retained session/recovery controls and in-flight send results.');
} finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
}
