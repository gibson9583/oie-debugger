// Exercise production JSX, response ordering and owner changes without a live engine.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const mock = `
import React from 'react';import {createRoot} from 'react-dom/client';
const query=new URLSearchParams(location.search), calls=[], subscriptions=[];
let enabled=!query.has('disabled'), manage=!query.has('read-only'), user={id:7};
let fail=query.get('fail'), hold=false, held=null, writeHeld=null;
const caps=()=>({enabled,configuredEnabled:enabled,available:enabled,userAllowed:true,managementAllowed:manage,
 apiVersion:1,commandProtocol:2,armRequestExpiresAt:30000,controlAllowed:true,globalAllowed:false,evaluationAllowed:false});
const source={id:'source-1',name:'channel-1_transformer_0',kind:'transformer',hash:'h',generation:1,text:'1 + 1;',breakableLines:[1],authoredMappings:[]};
window.fixture={calls,hold:()=>hold=true,release:()=>{held?.();held=null},finishWrite:()=>{writeHeld?.();writeHeld=null},
 user:()=>{user={id:8};subscriptions.forEach(fn=>fn())}};
const api={get:async(path)=>{calls.push(['GET',path]);if(path.endsWith('/capabilities')){
 const snapshot=JSON.stringify(caps());if(hold){hold=false;return new Promise(resolve=>held=()=>resolve(snapshot))}return snapshot;}
 if(path.endsWith('/sources'))return JSON.stringify([source]);if(path.includes('/sources/'))return JSON.stringify(source);
 throw new Error('Unexpected GET '+path)},
 put:async(path,text)=>{const body=JSON.parse(text);calls.push(['PUT',path,body]);
 if(!path.endsWith('/enabled'))throw new Error('Unexpected PUT '+path);
 if(fail==='revoke'){manage=false;throw Object.assign(new Error('Manage Extensions permission required'),{status:403})}
 if(query.has('hold-write'))await new Promise(resolve=>writeHeld=resolve);
 const changed=enabled!==body.enabled;enabled=body.enabled;
 if(fail==='lost'){fail=null;throw new Error('Response lost')}
 return JSON.stringify({...caps(),configurationChanged:changed})},
 channels:{list:async()=>[{id:'channel-1',name:'Test channel'}]},onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}};
export const platform={React,api,checkTask:()=>true,oie:{uuid:()=>crypto.randomUUID()},
 store:{getState:key=>key==='user'?user:null,setState:()=>{},subscribe:(key,fn)=>{subscriptions.push(fn);return()=>{}}},
 registerIcon:()=>{},registerNavItem:()=>{},registerChannelAction:()=>{},registerMessageAction:()=>{},reactView:v=>v,
 registerView:(path,View)=>createRoot(document.getElementById('root')).render(React.createElement(View)),
 createCodeEditor:opts=>{const el=document.createElement('div'),area=document.createElement('textarea');
 area.value=opts.value;area.setAttribute('aria-label',opts.ariaLabel);el.append(area);return{el,area,dispose:()=>{}}}};
`;
const bundle=await build({stdin:{contents:`import {register} from ${JSON.stringify(fileURLToPath(new URL('../web/plugin.jsx',import.meta.url)))};register();`,resolveDir:packageRoot},bundle:true,write:false,format:'esm',loader:{'.css':'text'},define:{'process.env.NODE_ENV':'"production"'},plugins:[{name:'host',setup(b){b.onResolve({filter:/^@oie\/web-shell$/},()=>({path:'host',namespace:'fixture'}));b.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:mock,loader:'js',resolveDir:packageRoot}))}}]});
const server=createServer((req,res)=>{res.setHeader('Content-Type',req.url==='/main.js'?'application/javascript':'text/html');res.end(req.url==='/main.js'?bundle.outputFiles[0].text:`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>:root{--font-ui:system-ui;--font-mono:monospace;--text:#d9e2ea;--text-dim:#a4b2c0;--bg1:#111922;--bg2:#16212c;--bg3:#1c2a38;--line:#233140;--line-strong:#567087;--accent:#58a6e8;--accent-press:#4690d1;--accent-ink:#06141f;--warn:#f0b541;--err:#ff5f5f;--ok:#3ddc84}body{margin:0}</style></head><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>`)});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;const errors=[];
const panel=p=>p.getByRole('region',{name:'Engine-wide debugging',exact:true});
const enable=p=>p.getByRole('button',{name:'Enable debugger',exact:true});
const disable=p=>p.getByRole('button',{name:'Disable debugger',exact:true});
const arm=p=>p.getByRole('button',{name:'Arm debugger',exact:true});
async function open(query=''){const page=await browser.newPage();page.setDefaultTimeout(5000);page.on('pageerror',e=>errors.push(e.message));await page.goto(`http://127.0.0.1:${server.address().port}/?${query}`);await expect(panel(page)).toBeVisible();return page;}
try{
 browser=await chromium.launch({headless:true});
 let page=await open();await expect(disable(page)).toBeEnabled();
 await disable(page).click();await expect(enable(page)).toBeEnabled();await expect(page.locator('.dbg-status')).toHaveText('Disabled');
 await expect(arm(page)).toBeDisabled();assert.equal(await page.getByRole('alert').count(),0,'Disabled is a normal state, not a channel error');
 await enable(page).click();await expect(disable(page)).toBeEnabled();await page.getByRole('combobox',{name:'Channel',exact:true}).selectOption('channel-1');await expect(arm(page)).toBeEnabled();
 assert.deepEqual(await page.evaluate(()=>fixture.calls.filter(c=>c[0]==='PUT').map(c=>c.slice(1))),[
 ['/extensions/debugger/enabled',{enabled:false}],['/extensions/debugger/enabled',{enabled:true}]]);await page.close();
 page=await open('disabled');await expect(enable(page)).toBeEnabled();await enable(page).focus();await page.keyboard.press('Enter');await expect(disable(page)).toBeEnabled();await page.close();
 page=await open('disabled&read-only');await expect(panel(page).getByText(/Manage Extensions/)).toBeVisible();await expect(enable(page)).toHaveCount(0);await page.close();
 page=await open('fail=revoke');await disable(page).click();await expect(page.getByRole('alert')).toContainText('Manage Extensions');await expect(disable(page)).toHaveCount(0);await expect(panel(page)).toContainText('Debugging enabled');await page.close();
 page=await open('fail=lost');await disable(page).click();await expect(page.getByRole('alert')).toContainText('Could not confirm');await expect(enable(page)).toBeEnabled();await expect(page.locator('.dbg-status')).toHaveText('Disabled');await page.close();
 page=await open();await expect(disable(page)).toBeEnabled();await page.evaluate(()=>{fixture.hold();window.dispatchEvent(new Event('focus'))});await disable(page).click();await expect(enable(page)).toBeEnabled();await page.evaluate(()=>fixture.release());await expect(page.locator('.dbg-status')).toHaveText('Disabled');await expect(disable(page)).toHaveCount(0);await page.close();
 page=await open('hold-write');await expect(disable(page)).toBeEnabled();await disable(page).click();await expect(page.getByRole('button',{name:'Saving…',exact:true})).toBeDisabled();await page.evaluate(()=>fixture.user());await page.evaluate(()=>fixture.finishWrite());await expect(page.getByRole('alert')).toContainText('login or engine changed');assert.equal(await page.evaluate(()=>fixture.calls.filter(c=>c[0]==='PUT').length),1);await page.close();
 assert.deepEqual(errors,[]);console.log('Enable/disable UI passed: defaults, saved-disabled, REST round trip, permission revocation, lost response, stale refresh and owner change.');
}finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
