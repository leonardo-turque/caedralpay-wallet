import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

async function page(available) {
  let click;
  const message={textContent:'Consultando disponibilidade…'};
  const button={disabled:true,textContent:'Baixar beta Android',addEventListener:(_event,callback)=>{click=callback;}};
  const requests=[];const downloads=[];
  const context={window:{},document:{
    querySelector:selector=>selector==='#download-status'?message:selector==='#download-button'?button:{append(){}},
    createElement:()=>({dataset:{}}),head:{append(){}}
  },location:{assign:url=>downloads.push(url)},fetch:async(url,options)=>{
    requests.push({url,options});
    return {ok:true,json:async()=>url==='/config.json'?{available,version:'0.1.0-beta.1',sitekey:'qa-site-key'}:
      available?{sha256:'a'.repeat(64),url:'/download?expires=123&sig=qa'}:{verified:true}};
  }};
  vm.runInNewContext(await readFile(new URL('./app.js',import.meta.url),'utf8'),context);
  await new Promise(resolve=>setImmediate(resolve));
  return {message,button,requests,downloads,context,click:()=>click()};
}
test('approved beta displays availability and requests an authorized download',async()=>{
  const view=await page(true);
  assert.match(view.message.textContent,/Versão 0\.1\.0-beta\.1 disponível/);
  assert.equal(view.button.disabled,true);
  view.context.window.onDownloadChallenge('qa-challenge');
  assert.equal(view.button.disabled,false);
  await view.click();
  assert.equal(view.requests[1].url,'/authorize');
  assert.deepEqual(view.downloads,['/download?expires=123&sig=qa']);
});
test('candidate displays verification only and never redirects to an APK',async()=>{
  const view=await page(false);
  assert.equal(view.button.textContent,'Validar proteção');
  view.context.window.onDownloadChallenge('qa-challenge');
  await view.click();
  assert.equal(view.requests[1].url,'/verify-challenge');
  assert.deepEqual(view.downloads,[]);
  assert.match(view.message.textContent,/Proteção validada/);
});
