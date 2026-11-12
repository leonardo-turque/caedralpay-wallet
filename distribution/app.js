const message=document.querySelector('#download-status');
const button=document.querySelector('#download-button');
let challenge='';
let downloadAvailable=false;
window.onDownloadChallenge=token=>{challenge=token;button.disabled=false;};
window.onDownloadExpired=()=>{challenge='';button.disabled=true;};
fetch('/config.json').then(r=>r.json()).then(config=>{
  downloadAvailable=config.available===true;
  if(downloadAvailable)message.textContent=`Versão ${config.version} disponível. Conclua a verificação para baixar.`;
  if(!downloadAvailable){message.textContent='Beta em validação. Você pode verificar a proteção de acesso abaixo.';button.textContent='Validar proteção';}
  if(!config.sitekey){message.textContent='Configuração de acesso em preparação.';return;}
  const widget=document.createElement('div');widget.className='cf-turnstile';widget.dataset.sitekey=config.sitekey;
  widget.dataset.action='download';widget.dataset.callback='onDownloadChallenge';widget.dataset.expiredCallback='onDownloadExpired';
  document.querySelector('#challenge').append(widget);
  const script=document.createElement('script');script.src='https://challenges.cloudflare.com/turnstile/v0/api.js';script.async=true;document.head.append(script);
}).catch(()=>{message.textContent='Download temporariamente indisponível.';});
button.addEventListener('click',async()=>{
  button.disabled=true;message.textContent='Verificando acesso…';
  try{
    const response=await fetch(downloadAvailable?'/authorize':'/verify-challenge',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:challenge})});
    const result=await response.json();if(!response.ok)throw Error();
    if(downloadAvailable){message.textContent=`SHA-256: ${result.sha256}`;location.assign(result.url);}
    else message.textContent='Proteção validada. O download será liberado após a validação do beta.';
  }catch{message.textContent='Verifique novamente o desafio e tente outra vez.';}
  challenge='';if(window.turnstile)window.turnstile.reset();
});
