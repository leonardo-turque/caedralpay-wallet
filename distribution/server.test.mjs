import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {once} from 'node:events';
import {createDownloadServer} from './server.mjs';
import http from 'node:http';

async function fixture(run,betaApproved=true) {
  const dir=await mkdtemp(`${tmpdir()}/caedral-download-`);
  const artifactPath=`${dir}/fixture.apk`;await writeFile(artifactPath,'fixture');
  const hostname='download.example.org';
  const server=createDownloadServer({hostname,sitekey:'fixture',turnstileSecret:'fixture',downloadSecret:'x'.repeat(64),
    artifactPath,manifest:{version:'0.1.0-beta.1',sha256:'a'.repeat(64),betaApproved},verifyToken:async token=>token.startsWith('valid-')});
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const request=(url,options={})=>new Promise((resolve,reject)=>{
    const req=http.request({hostname:'127.0.0.1',port:server.address().port,path:url,method:options.method??'GET',
      headers:{Host:hostname,'CF-Connecting-IP':'192.0.2.1',...options.headers}},res=>{
      let body='';res.on('data',chunk=>body+=chunk);res.on('end',()=>resolve({status:res.statusCode,
        json:async()=>JSON.parse(body),text:async()=>body,headers:{get:key=>res.headers[key]}}));
    });req.on('error',reject);req.end(options.body);
  });
  const authorize=token=>request('/authorize',{method:'POST',headers:{Origin:`https://${hostname}`,'Content-Type':'application/json'},body:JSON.stringify({token})});
  try {await run({request,authorize});} finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await rm(dir,{recursive:true});}
}
test('challenge produces a short-lived artifact link',()=>fixture(async({request,authorize})=>{
  const authorized=await authorize('valid-one');assert.equal(authorized.status,200);
  const {url}=await authorized.json();const download=await request(url);
  assert.equal(download.status,200);assert.equal(await download.text(),'fixture');
  assert.equal(download.headers.get('content-type'),'application/vnd.android.package-archive');
}));
test('same Turnstile token cannot be redeemed twice',()=>fixture(async({authorize})=>{
  assert.equal((await authorize('valid-one')).status,200);assert.equal((await authorize('valid-one')).status,403);
}));
test('tampering and another IP cannot use a download link',()=>fixture(async({request,authorize})=>{
  const {url}=await(await authorize('valid-one')).json();
  assert.equal((await request(url,{headers:{'CF-Connecting-IP':'192.0.2.2'}})).status,403);
  assert.equal((await request(url.replace(/sig=./,'sig=z'))).status,403);
  assert.equal((await request('/download?expires=1000000000&sig='+'a'.repeat(64))).status,403);
}));
test('invalid challenges, bad origin and filesystem paths are rejected',()=>fixture(async({request,authorize})=>{
  assert.equal((await authorize('invalid')).status,403);
  assert.equal((await request('/authorize',{method:'POST',headers:{Origin:'https://evil.example','Content-Type':'application/json'},body:'{}'})).status,403);
  assert.equal((await request('/releases/fixture.apk')).status,404);
  assert.equal((await request('/server.mjs')).status,404);
}));
test('candidate builds cannot be distributed even with a valid challenge',()=>fixture(async({authorize})=>{
  assert.equal((await authorize('valid-one')).status,503);
},false));
test('a malformed approval flag cannot enable distribution',()=>fixture(async({authorize})=>{
  assert.equal((await authorize('valid-one')).status,503);
},'false'));
test('authorization attempts have a per-IP rate limit',()=>fixture(async({authorize})=>{
  for(let i=0;i<5;i++)await authorize(`bad-${i}`);
  assert.equal((await authorize('valid-six')).status,429);
}));
test('protection can be validated without enabling candidate downloads',()=>fixture(async({request})=>{
  const result=await request('/verify-challenge',{method:'POST',headers:{Origin:'https://download.example.org','Content-Type':'application/json'},body:JSON.stringify({token:'valid-check'})});
  assert.equal(result.status,200);assert.deepEqual(await result.json(),{verified:true,downloadAvailable:false});
},false));
test('download managers can inspect HEAD without receiving the APK body',()=>fixture(async({request,authorize})=>{
  assert.equal((await request('/config.json',{method:'HEAD'})).status,200);
  const {url}=await(await authorize('valid-head')).json();const response=await request(url,{method:'HEAD'});
  assert.equal(response.status,200);assert.equal(await response.text(),'');
  assert.equal(Number(response.headers.get('content-length')),7);
}));
