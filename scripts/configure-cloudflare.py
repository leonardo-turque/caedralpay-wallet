#!/usr/bin/env python3
"""Interactive local credential collection. Does not edit DNS or create widgets."""
import getpass
import ipaddress
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError('Redirecionamento da API recusado.')

def main():
    directory=Path.home()/'.local/share/caedralpay/cloudflare'
    target=directory/'credentials.json'
    if target.exists(): raise SystemExit('Arquivo já existe; não foi sobrescrito: '+str(target))
    print('O token não será exibido nem salvo no repositório.')
    print('Permissões: Zone/DNS/Edit, Zone/Zone/Read e Account/Turnstile/Edit.')
    domain=input('Domínio na Cloudflare [caedral.com]: ').strip().lower() or 'caedral.com'
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}',domain): raise SystemExit('Domínio inválido.')
    host=input(f'Subdomínio de download [download.{domain}]: ').strip().lower() or f'download.{domain}'
    if not host.endswith('.'+domain) or not re.fullmatch(r'[a-z0-9.-]+',host): raise SystemExit('Subdomínio inválido.')
    origin=input('IP do servidor [13.140.152.67]: ').strip() or '13.140.152.67'
    ipaddress.ip_address(origin)
    token=getpass.getpass('Cloudflare API Token (entrada oculta): ').strip()
    if not token or any(c.isspace() for c in token): raise SystemExit('Token inválido.')
    request=urllib.request.Request('https://api.cloudflare.com/client/v4/zones?'+urllib.parse.urlencode({'name':domain}),
        headers={'Authorization':'Bearer '+token,'Accept':'application/json'})
    try:
        with urllib.request.build_opener(NoRedirect).open(request,timeout=15) as response:
            data=response.read(1_048_577)
            if len(data)>1_048_576: raise RuntimeError('Resposta excede o limite.')
            result=json.loads(data)
    except Exception:
        raise SystemExit('Não foi possível validar o acesso. Confira o token e a permissão Zone/Zone/Read; nenhum segredo foi impresso.')
    zones=result.get('result',[])
    if result.get('success') is not True or len(zones)!=1: raise SystemExit('O token não encontrou acesso único ao domínio informado.')
    zone=zones[0]
    if zone.get('status')!='active': raise SystemExit('A zona Cloudflare ainda não está ativa. Ative-a antes de configurar o download.')
    directory.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(directory,0o700)
    values={'apiToken':token,'zoneId':zone['id'],'accountId':zone['account']['id'],'domain':domain,'hostname':host,'originIp':origin}
    flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0)
    with os.fdopen(os.open(target,flags,0o600),'w') as stream:json.dump(values,stream)
    print('Criado: '+str(target))
    print('Agora responda apenas: criado. Não copie o conteúdo do arquivo para o chat.')

if __name__=='__main__':
    try:main()
    except (KeyboardInterrupt,EOFError):raise SystemExit('\nCancelado; nenhum token foi exibido.')
