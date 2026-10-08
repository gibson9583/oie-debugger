// Mock-host regression using local React/Playwright; no live messages.
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const mock = `
import React from 'react';
import {createRoot} from 'react-dom/client';
const mode=new URLSearchParams(location.search).get('mode');
let target={channelId:'channel-1',...(mode==='new-message'?{}:{messageId:'17'}),connectorId:2},session,serial=0;
const calls=[],user={id:'user-1'},listeners=[];
const fixture=window.fixture={calls,target:()=>target,setPaused:()=>{session.status='paused';},logout:()=>{user.id='user-2';listeners.forEach(fn=>fn());}};
const source={id:'source-1',name:'transformer_2',kind:'transformer',hash:'hash',generation:1,text:'value;',lineCount:1,breakableLines:[1],authoredMappings:[]};
const api={
 get:async(path)=>{if(path.endsWith('/capabilities'))return JSON.stringify({available:true,apiVersion:1,controlAllowed:true,evaluationAllowed:true,globalAllowed:true});if(path.endsWith('/sources'))return JSON.stringify([source]);if(path.includes('/sources/'))return JSON.stringify(source);return JSON.stringify(session);},
 post:async(path,text)=>{const body=JSON.parse(text);calls.push(['POST',path,body]);if(path.endsWith('/sessions')){if(mode==='arm-fails'&&serial)throw new Error('New session failed');session={id:'session-'+(++serial),channelId:body.channelId,status:'armed',pauseGeneration:0};return JSON.stringify(session);}return JSON.stringify({status:'completed'});},
 del:async(path)=>{calls.push(['DELETE',path]);if(mode==='delete-fails')throw new Error('Delete outcome unknown');if(mode==='deferred-delete')await new Promise(resolve=>fixture.releaseDelete=resolve);session=null;return '{}';},
 messages:{processNew:async(...args)=>{calls.push(['SEND',...args]);return 71;}},channels:{list:async()=>[{id:'channel-1',name:'Test channel'}]},onSessionExpired:()=>()=>{},onEngineUnknown:()=>()=>{}
};
export const platform={React,api,checkTask:()=>true,oie:{uuid:()=>crypto.randomUUID()},store:{getState:key=>key==='user'?user:key==='debuggerTarget'?target:null,setState:(key,value)=>{if(key==='debuggerTarget')target=value;},subscribe:(key,fn)=>{listeners.push(fn);return()=>{};}},router:{navigate:()=>{}},registerIcon:()=>{},registerNavItem:()=>{},registerChannelAction:()=>{},registerMessageAction:()=>{},reactView:component=>component,registerView:(path,View)=>createRoot(document.getElementById('root')).render(React.createElement(View)),createCodeEditor:opts=>{const el=document.createElement('div'),area=document.createElement('textarea');area.readOnly=true;area.value=opts.value;el.append(area);return{el,area,dispose:()=>{}}}};
`;
const bundle=await build({stdin:{contents:`import {register} from ${JSON.stringify(fileURLToPath(new URL('../web/plugin.jsx',import.meta.url)))};register();`,resolveDir:packageRoot},bundle:true,write:false,format:'esm',loader:{'.css':'text'},define:{'process.env.NODE_ENV':'"production"'},plugins:[{name:'host-fixture',setup(build){build.onResolve({filter:/^@oie\/web-shell$/},()=>({path:'host',namespace:'fixture'}));build.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:mock,loader:'js',resolveDir:packageRoot}));}}]});
const server=createServer((req,res)=>{res.setHeader('Content-Type',req.url==='/main.js'?'application/javascript':'text/html');res.end(req.url==='/main.js'?bundle.outputFiles[0].text:'<!doctype html><html><body><div id="root"></div><script type="module" src="/main.js"></script></body></html>');});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
const draft='  {"id": 17, "value": "hello"}\n';
async function armed(mode='') {
 const page=await browser.newPage();
 page.setDefaultTimeout(10000);
 await page.goto(`http://127.0.0.1:${server.address().port}/?mode=${mode}`);
 await page.getByText('Limit the script surface',{exact:true}).click();
 await page.getByLabel('Script kind',{exact:true}).fill('transformer');
 await page.getByLabel('Exact deployed source').selectOption('source-1');
 await page.getByLabel('Maximum pause (seconds)',{exact:true}).fill('45');
 await page.getByRole('button',{name:'Arm debugger',exact:true}).click();
 await page.locator('.dbg-send textarea').fill(draft);
 assert.equal(await page.getByRole('button',{name:'Send message',exact:true}).isDisabled(),true);
 assert.equal(await page.getByLabel('Message ID (optional)',{exact:true}).count(),0);
 return page;
}
try {
 browser=await chromium.launch({headless:true});
 let page=await browser.newPage();
 await page.goto(`http://127.0.0.1:${server.address().port}/?mode=new-message`);
 const input=page.getByRole('textbox',{name:'Message',exact:true});
 const submit=page.getByRole('button',{name:'Send message',exact:true});
 assert.equal(await input.inputValue(),'');
 assert.equal(await submit.isDisabled(),true);
 assert.deepEqual(await page.locator('.dbg-send button').allTextContents(),['Send message']);
 await page.getByRole('button',{name:'Arm debugger',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('.dbg-status')?.textContent==='armed');
 assert.equal(await input.inputValue(),'');
 assert.equal(await submit.isDisabled(),true);
 await input.fill(' \n ');
 assert.equal(await submit.isDisabled(),true);
 const messages=[draft,'<message id="18">Hello &amp; goodbye</message>\n','  Plain text: café 日本語 😀\nSecond line\t  '];
 for (const message of messages) {
   await input.fill(message);
   await submit.click();
   await page.waitForFunction(()=>!document.querySelector('.dbg-send button[type="submit"]').disabled);
 }
 assert.deepEqual(await page.evaluate(()=>fixture.calls.filter(call=>call[0]==='SEND')),messages.map(message=>['SEND','channel-1',message,null,[]]));
 await page.close();

 page=await armed();
 await page.getByRole('button',{name:'Arm for a new message',exact:true}).click();
 const send=page.getByRole('button',{name:'Send message',exact:true});
 await page.waitForFunction(()=>!document.querySelector('.dbg-send button[type="submit"]').disabled);
 assert.equal(await page.locator('.dbg-send textarea').inputValue(),draft);
 const beforeSend=await page.evaluate(()=>({calls:fixture.calls,target:fixture.target()}));
 const arms=beforeSend.calls.filter(call=>call[0]==='POST'&&call[1].endsWith('/sessions'));
 assert.equal(arms.length,2);
 const {messageId,...original}=arms[0][2];
 assert.equal(messageId,'17');assert.deepEqual(arms[1][2],original);
 assert(beforeSend.calls.findIndex(call=>call[0]==='DELETE')<beforeSend.calls.indexOf(arms[1]));
 assert.equal(Object.hasOwn(beforeSend.target,'messageId'),false);
 assert.equal(beforeSend.target.connectorId,2);
 assert.equal(beforeSend.calls.filter(call=>call[0]==='SEND').length,0);
 await send.click();await page.getByText('Message 71 accepted by the engine.',{exact:true}).waitFor();
 const sent=await page.evaluate(()=>fixture.calls.filter(call=>call[0]==='SEND'));
 assert.deepEqual(sent,[['SEND','channel-1',draft,null,[]]]);
 await page.close();

 page=await armed('delete-fails');
 await page.getByRole('button',{name:'Arm for a new message',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'Delete outcome unknown'}).waitFor();
 assert.equal(await page.evaluate(()=>fixture.calls.filter(call=>call[0]==='POST'&&call[1].endsWith('/sessions')).length),1);
 assert.equal(await page.evaluate(()=>fixture.target().messageId),'17');
 assert.equal(await page.locator('.dbg-send textarea').inputValue(),draft);
 assert.equal(await page.getByRole('button',{name:'Send message',exact:true}).isDisabled(),true);
 await page.close();

 page=await armed('deferred-delete');
 await page.getByRole('button',{name:'Arm for a new message',exact:true}).click();
 await page.waitForFunction(()=>typeof fixture.releaseDelete==='function');
 assert.equal(await page.getByRole('button',{name:'Arm for a new message',exact:true}).isDisabled(),true);
 await page.evaluate(()=>{fixture.logout();fixture.releaseDelete();});
 await page.getByRole('alert').filter({hasText:'login or engine changed'}).waitFor();
 assert.equal(await page.evaluate(()=>fixture.calls.filter(call=>call[0]==='POST'&&call[1].endsWith('/sessions')).length),1);
 await page.close();

 page=await armed();await page.evaluate(()=>fixture.setPaused());
 await page.waitForFunction(()=>document.querySelector('.dbg-status')?.textContent==='paused');
 assert.equal(await page.getByRole('button',{name:'Arm for a new message',exact:true}).isDisabled(),true);
 assert.equal(await page.evaluate(()=>fixture.calls.filter(call=>call[0]==='DELETE').length),0);
 await page.close();

 page=await armed('arm-fails');
 await page.getByRole('button',{name:'Arm for a new message',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'New session failed'}).waitFor();
 assert.equal(await page.getByLabel('Message ID (optional)',{exact:true}).inputValue(),'');
 assert.equal(await page.locator('.dbg-send textarea').inputValue(),draft);
 assert.equal(await page.evaluate(()=>Object.hasOwn(fixture.target(),'messageId')),false);
 assert.equal(await page.evaluate(()=>fixture.calls.filter(call=>call[0]==='SEND').length),0);
 await page.close();
 console.log('New-message browser regression passed: explicit switch preserves draft/options, clears stale filter, sends once, and guards failed DELETE, identity changes, paused state and failed rearm.');
} finally {await browser?.close();await new Promise(resolve=>server.close(resolve));}
