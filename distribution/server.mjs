import http from 'node:http';
import {createReadStream} from 'node:fs';
import {readFile, stat} from 'node:fs/promises';
import {createHash, createHmac, timingSafeEqual} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export function createDownloadServer(config) {
  const {hostname, sitekey, turnstileSecret, downloadSecret, manifest, artifactPath} = config;
  if (!/^[a-z0-9.-]+$/.test(hostname) || (manifest.betaApproved === true &&
      (!sitekey || !turnstileSecret || typeof downloadSecret !== 'string' || downloadSecret.length < 32))) throw Error('Download configuration missing');
  const verifiedTokens = new Map();
  const rates = new Map();
  const root = path.dirname(fileURLToPath(import.meta.url));
  const signature = (expires, ip) => createHmac('sha256', downloadSecret).update(`${manifest.sha256}|${expires}|${ip}`).digest('hex');
  const verify = config.verifyToken ?? (async token => {
    const response = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {
      method:'POST', body:new URLSearchParams({secret:turnstileSecret,response:token}), signal:AbortSignal.timeout(8000)
    });
    if (!response.ok) return false;
    const result = await response.json();
    return result.success === true && result.hostname === hostname && result.action === 'download';
  });
  return http.createServer(async (req,res) => {
    const reply = (code, body) => {res.writeHead(code, {'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(body));};
    try {
      res.setHeader('X-Content-Type-Options','nosniff');
      res.setHeader('Referrer-Policy','no-referrer');
      res.setHeader('Content-Security-Policy', "default-src 'none'; style-src 'self'; script-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; connect-src 'self' https://challenges.cloudflare.com; base-uri 'none'; frame-ancestors 'none'");
      if (req.headers.host !== hostname) return reply(400,{error:'host'});
      const url = new URL(req.url, `https://${hostname}`);
      // Bind on localhost behind Nginx. Only trust CF headers when the origin is
      // restricted to Cloudflare; never expose this listener directly.
      const ip = req.headers['cf-connecting-ip'] ?? req.socket.remoteAddress;
      if (typeof ip !== 'string' || ip.length > 64) return reply(400,{error:'ip'});
      const now = Date.now();
      for (const [key,until] of verifiedTokens) if (until < now) verifiedTokens.delete(key);
      for (const [key,value] of rates) if (value.until < now) rates.delete(key);
      if (req.method === 'GET' && url.pathname === '/config.json') return reply(200,{sitekey,version:manifest.version,available:manifest.betaApproved === true});
      if (req.method === 'POST' && ['/authorize','/verify-challenge'].includes(url.pathname)) {
        if (req.headers.origin !== `https://${hostname}` || req.headers['content-type'] !== 'application/json') return reply(403,{error:'origin'});
        const bucket = rates.get(ip) ?? {count:0,until:now+60_000}; rates.set(ip,bucket);
        if (++bucket.count > 5 || rates.size > 10000 || verifiedTokens.size > 10000) return reply(429,{error:'rate'});
        if (manifest.betaApproved !== true && url.pathname === '/authorize') return reply(503,{error:'A versão ainda está em validação.'});
        let body='';
        for await (const chunk of req) { body+=chunk; if (body.length > 4096) return reply(413,{error:'size'}); }
        const {token} = JSON.parse(body);
        if (typeof token !== 'string' || !token || token.length > 2048) return reply(400,{error:'token'});
        const tokenHash = createHash('sha256').update(token).digest('hex');
        if (verifiedTokens.has(tokenHash)) return reply(403,{error:'replay'});
        verifiedTokens.set(tokenHash,now+300_000);
        if (!await verify(token)) return reply(403,{error:'challenge'});
        if (url.pathname === '/verify-challenge') return reply(200,{verified:true,downloadAvailable:manifest.betaApproved === true});
        const expires = Math.floor(now/1000)+120;
        return reply(200,{url:`/download?expires=${expires}&sig=${signature(expires,ip)}`,sha256:manifest.sha256});
      }
      if (req.method === 'GET' && url.pathname === '/download') {
        const expires = url.searchParams.get('expires'); const sig = url.searchParams.get('sig');
        if (manifest.betaApproved !== true || !/^\d{10}$/.test(expires??'') || !/^[a-f0-9]{64}$/.test(sig??'') ||
            Number(expires)<now/1000 || Number(expires)>now/1000+121 || !timingSafeEqual(Buffer.from(sig,'hex'),Buffer.from(signature(expires,ip),'hex')))
          return reply(403,{error:'expired'});
        const info = await stat(artifactPath);
        res.writeHead(200,{'Content-Type':'application/vnd.android.package-archive','Content-Length':info.size,
          'Content-Disposition':`attachment; filename="caedralpay-wallet-${manifest.version}.apk"`,'Cache-Control':'private, no-store'});
        createReadStream(artifactPath).on('error',()=>res.destroy()).pipe(res); return;
      }
      const files = new Map([['/','index.html'],['/index.html','index.html'],['/style.css','style.css'],['/app.js','app.js'],['/privacy.html','privacy.html']]);
      if (req.method === 'GET' && files.has(url.pathname)) {
        const file = files.get(url.pathname);
        res.writeHead(200,{'Content-Type':file.endsWith('.css')?'text/css':file.endsWith('.js')?'text/javascript':'text/html; charset=utf-8'});
        return res.end(await readFile(path.join(root,file)));
      }
      reply(404,{error:'not-found'});
    } catch { if (!res.headersSent) reply(503,{error:'Serviço indisponível.'}); else res.destroy(); }
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const manifest = JSON.parse(await readFile(process.env.CAEDRAL_RELEASE_MANIFEST,'utf8'));
  const artifactPath = process.env.CAEDRAL_RELEASE_APK;
  const hash = createHash('sha256').update(await readFile(artifactPath)).digest('hex');
  if (hash !== manifest.sha256 || !/^\d+\.\d+\.\d+-beta\.\d+$/.test(manifest.version)) throw Error('Artifact verification failed');
  createDownloadServer({hostname:process.env.CAEDRAL_DOWNLOAD_HOST,sitekey:process.env.TURNSTILE_SITE_KEY,
    turnstileSecret:process.env.TURNSTILE_SECRET_KEY,downloadSecret:process.env.DOWNLOAD_TOKEN_SECRET,
    artifactPath,manifest}).listen(8097,'127.0.0.1');
}
