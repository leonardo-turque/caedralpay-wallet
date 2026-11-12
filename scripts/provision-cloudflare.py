#!/usr/bin/env python3
"""Prepare/apply only the wallet download DNS record and its dedicated Turnstile widget."""
from pathlib import Path
import argparse, json, os, re, secrets, stat, subprocess, urllib.parse

parser=argparse.ArgumentParser()
parser.add_argument('--apply',action='store_true')
parser.add_argument('--proxy',action='store_true',help='Enable Cloudflare proxy only after origin TLS is verified.')
parser.add_argument('--ssl-status',action='store_true',help='Read SSL mode without changing any zone settings.')
args=parser.parse_args()
file=Path.home()/'.local/share/caedralpay/cloudflare/credentials.json'
if not file.is_file() or file.is_symlink() or stat.S_IMODE(file.stat().st_mode)&0o077:
    raise SystemExit('Execute configure-cloudflare.py; o arquivo precisa ser privado e não pode ser symlink.')
settings=json.loads(file.read_text())
zone=settings['zoneId'];account=settings['accountId'];host=settings['hostname']
if not all(re.fullmatch(r'[a-f0-9]{32}',value) for value in [zone,account]): raise SystemExit('IDs inválidos.')

def call(path,method='GET',body=None):
    # Config goes through stdin: credentials never enter argv, shell history or logs.
    config='url = '+json.dumps('https://api.cloudflare.com/client/v4'+path)+'\n'
    config+='request = '+json.dumps(method)+'\nheader = '+json.dumps('Authorization: Bearer '+settings['apiToken'])+'\n'
    config+='header = "Content-Type: application/json"\n'
    if body is not None:config+='data = '+json.dumps(json.dumps(body))+'\n'
    try:
        response=subprocess.run(['curl','--silent','--show-error','--max-time','20','--max-filesize','1048576','--config','-'],
            input=config,capture_output=True,text=True,timeout=25,check=True)
        result=json.loads(response.stdout)
    except Exception:
        raise SystemExit('Cloudflare indisponível ou resposta inválida. Nenhum segredo foi impresso.')
    if result.get('success') is not True:
        codes=[error.get('code') for error in result.get('errors',[])]
        raise SystemExit('Cloudflare recusou a operação. Códigos: '+str(codes)+'. Confira as permissões do token.')
    return result['result']

def save():
    temp=file.with_name('.credentials-'+secrets.token_hex(8))
    with os.fdopen(os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump(settings,stream);stream.flush();os.fsync(stream.fileno())
    os.replace(temp,file)

if args.ssl_status:
    print('Cloudflare SSL mode:',call(f'/zones/{zone}/settings/ssl').get('value'))
    raise SystemExit(0)
records=call(f'/zones/{zone}/dns_records?'+urllib.parse.urlencode({'name':host}))
if len(records)>1 or records and (records[0]['type']!='A' or records[0]['content']!=settings['originIp']):
    raise SystemExit('Registro existente em conflito. Nenhum registro de outro serviço foi alterado.')
name='CaedralPay Wallet beta - '+host
widgets=call(f'/accounts/{account}/challenges/widgets')
matching=[w for w in widgets if w.get('name')==name and w.get('domains')==[host]]
if len(matching)>1:raise SystemExit('Mais de um widget correspondente; revisar antes de alterar.')
print('DNS:',host,'->',settings['originIp'],'(existente)' if records else '(novo)')
print('Turnstile dedicado:', 'existente' if matching else 'a criar')
if not args.apply:
    print('Plano somente. Nenhum DNS ou widget foi modificado.');raise SystemExit(0)
if matching:
    widget=call(f'/accounts/{account}/challenges/widgets/'+matching[0]['sitekey'])
else:
    widget=call(f'/accounts/{account}/challenges/widgets','POST',{'name':name,'domains':[host],'mode':'managed'})
if not widget.get('sitekey') or not widget.get('secret'):raise SystemExit('Widget sem credenciais completas; não será configurado um segredo fictício.')
settings['turnstileSitekey']=widget['sitekey'];settings['turnstileSecret']=widget['secret'];save()
record={'type':'A','name':host,'content':settings['originIp'],'ttl':1,
    'proxied':True if args.proxy else bool(records and records[0].get('proxied')),
    'comment':'CaedralPay Wallet beta downloads'}
if records:result=call(f'/zones/{zone}/dns_records/'+records[0]['id'],'PATCH',record)
else:result=call(f'/zones/{zone}/dns_records','POST',record)
settings['dnsRecordId']=result['id'];save()
print('DNS e widget configurados. Credenciais permaneceram somente no arquivo privado.')
print('Proxy:',result.get('proxied'),'; configure/verifique o TLS da origem antes da publicação.')
