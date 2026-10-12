// Lost creation responses in the production JSX/client, with an idempotent host.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const mock = `
import React from 'react';
import {createRoot} from 'react-dom/client';
const query=new URLSearchParams(location.search),mode=query.get('mode');
let target={channelId:'channel-1'},user={id:'user-1'},session,root,serial=0,capabilities=0,armCalls=0,sourceHeld=false;
const calls=[],listeners=[],requests=new Map();
const source=generation=>({id:'source-1',name:'transformer_0',kind:'transformer',hash:'hash-'+generation,generation,text:'source generation '+generation,lineCount:1,breakableLines:[1],authoredMappings:[]});
const fixture=window.fixture={calls,requests,created:()=>serial,session:()=>session,
    pause:()=>{session={...session,status:'paused',pauseGeneration:1,frames:[{index:0,sourceId:'source-1',sourceHash:'hash-2',sourceGeneration:2,line:1}]};requests.set(session.requestId,session);},
    close:()=>{session={...session,status:'closed',closeReason:'lease expired'};requests.set(session.requestId,session);},
    logout:()=>{user={id:'user-2'};listeners.forEach(fn=>fn());},unmount:()=>root.unmount()
};
const api={
    get:async(path,params)=>{
        calls.push(['GET',path,params]);
        if(path.endsWith('/capabilities')){
            capabilities++;
            return JSON.stringify({available:true,apiVersion:1,controlAllowed:true,
                evaluationAllowed:mode==='watches'||mode==='revoke-engine'||(mode==='revoke-evaluation'&&capabilities===1),
                evaluationEnabled:mode!=='revoke-engine'||capabilities===1,
                globalAllowed:mode==='revoke-global'&&capabilities===1,armRequestExpiresAt:Date.now()+60000+capabilities});
        }
        if(path.endsWith('/sources'))return JSON.stringify([source(1)]);
        if(path.includes('/sources/')){
            if(query.has('delaySource')&&!sourceHeld){sourceHeld=true;return await new Promise(resolve=>fixture.releaseSource=()=>resolve(JSON.stringify({...source(0),text:'stale source'})));}
            return JSON.stringify(source(params.debugSessionId&&session?.status==='paused'?2:1));
        }
        if(path.includes('/sessions/'))return JSON.stringify(session);
        throw new Error('Unexpected GET '+path);
    },
    post:async(path,text)=>{
        const body=JSON.parse(text);calls.push(['POST',path,body]);
        if(path.endsWith('/sessions')){
            armCalls++;
            if(mode?.startsWith('revoke-')&&armCalls===1)throw Object.assign(new Error('Requested option is no longer allowed'),{status:403});
            if(mode==='expired'&&armCalls===2){fixture.close();throw Object.assign(new Error('Arm request expired'),{status:409,body:JSON.stringify({error:'Arm request expired',code:'arm_request_rejected',requestId:body.requestId})});}
            if(!requests.has(body.requestId)){
                session={id:'session-'+(++serial),requestId:body.requestId,channelId:body.channelId,status:'armed',pauseGeneration:0};requests.set(body.requestId,session);
            }
            if(armCalls===1&&!mode?.startsWith('revoke-'))throw Object.assign(new Error('Arm response interrupted'),mode==='server-error'?{status:503}:{});
            if(mode==='retry-lost'&&armCalls===2)throw new Error('Recovery response interrupted');
            if(mode?.startsWith('retry-denied-')&&armCalls===2)throw Object.assign(new Error('Recovery denied without closure proof'),{status:Number(mode.split('-').pop())});
            if(mode==='retry-wrong-proof'&&armCalls===2)throw Object.assign(new Error('Recovery denied without closure proof'),{status:403,body:JSON.stringify({error:'Denied',code:'arm_request_rejected',requestId:'different-request'})});
            if(mode==='logout'||mode==='unmount')await new Promise(resolve=>fixture.releaseArm=resolve);
            return JSON.stringify(requests.get(body.requestId));
        }
        if(path.endsWith('/heartbeat'))return '{}';
        if(path.endsWith('/commands'))return JSON.stringify({status:'completed'});
        throw new Error('Unexpected POST '+path);
    },
    del:async(path)=>{calls.push(['DELETE',path]);if(!path.endsWith('/'+session.id))throw new Error('Wrong session ended');fixture.close();return '{}';},
    channels:{list:async()=>[{id:'channel-1',name:'Recovery channel'}]},onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}
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
const check = page => page.getByRole('button', { name: 'Check arm result', exact: true });
const arms = page => page.evaluate(() => fixture.calls.filter(call => call[0] === 'POST' && call[1].endsWith('/sessions')));
async function pending(query = '') {
    const page = await browser.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/?${query}`);
    const submit = page.getByRole('button', { name: 'Arm debugger', exact: true });
    await expect(submit).toBeEnabled();
    if (query.includes('delaySource')) await page.waitForFunction(() => typeof fixture.releaseSource === 'function');
    if (query.includes('mode=watches')) {
        await page.getByRole('checkbox', { name: 'Enable evaluation and value editing', exact: true }).check();
        await page.getByRole('textbox', { name: 'Watch expressions (one per line)', exact: true }).fill('msg\nchannelMap');
    }
    await submit.click();
    await expect(check(page)).toBeEnabled();
    await expect(page.getByRole('alert')).toContainText('The arm result is unknown');
    await expect(submit).toBeDisabled();
    await expect(page.getByRole('combobox', { name: 'Channel', exact: true })).toBeDisabled();
    await expect(page.getByLabel('Message ID (optional)', { exact: true })).toBeDisabled();
    await expect(page.getByRole('checkbox', { name: 'Break on entry', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Pause next line', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'End session', exact: true })).toHaveCount(0);
    if (query.includes('mode=watches')) {
        await expect(page.getByRole('textbox', { name: 'Watch expressions (one per line)', exact: true })).toBeDisabled();
        await expect(page.getByRole('textbox', { name: 'Watch expressions (one per line)', exact: true })).toHaveValue('msg\nchannelMap');
    }
    // Neither Enter nor a stale/programmatic form submit may create a new lease.
    await page.keyboard.press('Enter');
    await page.evaluate(() => document.querySelector('.dbg-target').closest('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    await expect(page.getByRole('alert')).toContainText('Check the previous arm result');
    assert.equal((await arms(page)).length, 1);
    assert.equal(await page.evaluate(() => fixture.created()), 1);
    return page;
}
async function assertSameRequest(page, expected = 2) {
    const requests = await arms(page);
    assert.equal(requests.length, expected);
    for (const request of requests.slice(1)) assert.deepEqual(request[2], requests[0][2]);
    assert.equal(await page.evaluate(() => fixture.created()), 1, 'all checks must recover the original lease');
    assert.equal(await page.evaluate(() => fixture.calls.filter(call => call[1].endsWith('/capabilities')).length), 2, 'recovery must not replace the original server deadline');
}

try {
    browser = await chromium.launch({ headless: true });
    for (const mode of ['', 'server-error', 'retry-lost', 'watches', 'retry-denied-401', 'retry-denied-403', 'retry-denied-409', 'retry-denied-429', 'retry-wrong-proof']) {
        const page = await pending(`mode=${mode}&delaySource`);
        await expect(page.getByLabel('Deployed JavaScript source', { exact: true })).toHaveValue('source generation 1');
        await page.evaluate(() => fixture.pause());
        await check(page).click();
        const retryInterrupted = mode.startsWith('retry-');
        if (retryInterrupted) {
            await expect(page.getByRole('alert')).toContainText(mode === 'retry-lost' ? 'Recovery response interrupted' : 'Recovery denied without closure proof');
            await expect(check(page)).toBeEnabled();
            await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeDisabled();
            assert.equal(await page.evaluate(() => fixture.created()), 1);
            await check(page).click();
        }
        await expect(page.locator('.dbg-status')).toHaveText('paused');
        await expect(page.getByRole('button', { name: 'Continue', exact: true })).toBeEnabled();
        await expect(check(page)).toHaveCount(0);
        if (mode === 'watches') {
            await expect(page.getByRole('textbox', { name: 'Watch expressions (one per line)', exact: true })).toBeEnabled();
            assert.deepEqual((await arms(page))[0][2].watches, ['msg', 'channelMap']);
        }
        await expect(page.getByLabel('Deployed JavaScript source', { exact: true })).toHaveValue('source generation 2');
        await page.evaluate(() => fixture.releaseSource());
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        await expect(page.getByLabel('Deployed JavaScript source', { exact: true })).toHaveValue('source generation 2');
        await assertSameRequest(page, retryInterrupted ? 3 : 2);
        await page.getByRole('button', { name: 'End session', exact: true }).click();
        await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
        assert.deepEqual(await page.evaluate(() => fixture.calls.filter(call => call[0] === 'DELETE')), [['DELETE', '/extensions/debugger/sessions/session-1']]);
        assert.equal(await page.evaluate(() => fixture.session().status), 'closed');
        await page.close();
    }

    for (const mode of ['revoke-evaluation', 'revoke-engine', 'revoke-global']) {
        const page = await browser.newPage();
        page.setDefaultTimeout(5000);
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(`http://127.0.0.1:${server.address().port}/?mode=${mode}`);
        const option = page.getByRole('checkbox', { name: mode === 'revoke-global' ? 'Include lifecycle and scripts without a message' : 'Enable evaluation and value editing', exact: true });
        await expect(option).toBeEnabled();
        await option.check();
        await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
        await expect(page.getByRole('alert')).toHaveText('Requested option is no longer allowed');
        await expect(check(page)).toHaveCount(0);
        assert.equal(await page.evaluate(() => fixture.created()), 0);
        await expect(option).toBeChecked();
        await expect(option).toBeEnabled();
        await option.uncheck();
        await expect(option).not.toBeChecked();
        await expect(option).toBeDisabled();
        await page.getByRole('button', { name: 'Arm debugger', exact: true }).click();
        await expect(page.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
        const submissions = await arms(page);
        assert.equal(submissions.length, 2);
        assert.notEqual(submissions[0][2].requestId, submissions[1][2].requestId);
        assert.equal(submissions[1][2].evaluationEnabled, false);
        assert.equal(submissions[1][2].includeLifecycle, false);
        assert.equal(await page.evaluate(() => fixture.created()), 1);
        await page.close();
    }

    const closed = await pending();
    await closed.evaluate(() => fixture.close());
    await check(closed).click();
    await expect(closed.getByRole('button', { name: 'Clear session', exact: true })).toBeEnabled();
    await expect(closed.getByText('Session ended: lease expired. Clear the session to arm again.', { exact: true })).toBeVisible();
    await assertSameRequest(closed);
    await closed.getByRole('button', { name: 'Clear session', exact: true }).click();
    await expect(closed.getByRole('button', { name: 'Arm debugger', exact: true })).toBeEnabled();
    assert.equal(await closed.evaluate(() => fixture.calls.filter(call => call[0] === 'DELETE').length), 0);
    await closed.close();

    const expired = await pending('mode=expired');
    await check(expired).click();
    await expect(expired.getByRole('alert')).toHaveText('Arm request expired');
    await expect(check(expired)).toHaveCount(0);
    await expect(expired.getByRole('combobox', { name: 'Channel', exact: true })).toBeEnabled();
    await expired.getByRole('button', { name: 'Arm debugger', exact: true }).click();
    await expect(expired.getByRole('button', { name: 'End session', exact: true })).toBeEnabled();
    const requests = await arms(expired);
    assert.equal(requests.length, 3);
    assert.deepEqual(requests[1][2], requests[0][2]);
    assert.notEqual(requests[2][2].requestId, requests[0][2].requestId);
    assert.notEqual(requests[2][2].requestExpiresAt, requests[0][2].requestExpiresAt);
    assert.equal(await expired.evaluate(() => fixture.created()), 2);
    await expired.close();

    for (const mode of ['logout', 'unmount']) {
        const page = await pending(`mode=${mode}`);
        await check(page).click();
        await page.waitForFunction(() => typeof fixture.releaseArm === 'function');
        await expect(check(page)).toBeDisabled();
        await page.evaluate(mode => { fixture[mode](); fixture.releaseArm(); }, mode);
        if (mode === 'logout') {
            await expect(page.getByRole('alert')).toContainText('login or engine changed');
            await expect(page.getByRole('button', { name: 'Arm debugger', exact: true })).toBeDisabled();
        } else await expect(page.locator('#root')).toBeEmpty();
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        await expect(page.getByRole('button', { name: 'End session', exact: true })).toHaveCount(0);
        await assertSameRequest(page);
        const deletes = await page.evaluate(() => fixture.calls.filter(call => call[0] === 'DELETE'));
        assert.deepEqual(deletes, mode === 'unmount' ? [['DELETE', '/extensions/debugger/sessions/session-1']] : []);
        assert.equal(await page.evaluate(() => fixture.session().status), mode === 'unmount' ? 'closed' : 'armed');
        await page.close();
    }
    assert.deepEqual(errors, []);
    console.log('Arm-recovery browser regression passed: lost/network/503 and repeated lost responses reuse one request/session; recovery 401/403/409/429 or wrong-request proof retain the original pending lease; pending settings/watches and stale submissions are blocked; paused/closed recovery, End/Clear, proven expired request then fresh Arm, owner/unmount fencing and cleanup, delayed source recovery; revoked evaluation permission/engine setting/global permission can be cleared before ordinary Arm.');
} finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
}
