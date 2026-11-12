#!/usr/bin/env python3
"""Send only the dedicated widget secret to the verified SSH host, via stdin."""
from pathlib import Path
import json, os, re, secrets, stat, subprocess

source=Path.home()/'.local/share/caedralpay/cloudflare/credentials.json'
if source.is_symlink() or stat.S_IMODE(source.stat().st_mode)&0o077:raise SystemExit('Credenciais precisam ser privadas.')
data=json.loads(source.read_text())
if not all(data.get(k) for k in ['turnstileSitekey','turnstileSecret','hostname']):raise SystemExit('Execute provision-cloudflare.py --apply primeiro.')
if not re.fullmatch(r'[a-z0-9.-]+',data['hostname']):raise SystemExit('Hostname inválido.')
for key in ['turnstileSitekey','turnstileSecret']:
    if not re.fullmatch(r'[A-Za-z0-9_-]+',data[key]):raise SystemExit('Credencial com formato inesperado.')
target=source.parent/'download.env'
secret=secrets.token_hex(32)
if target.is_file():
    if target.is_symlink() or stat.S_IMODE(target.stat().st_mode)&0o077:raise SystemExit('Configuração local precisa ser privada.')
    for line in target.read_text().splitlines():
        if line.startswith('DOWNLOAD_TOKEN_SECRET='):secret=line.split('=',1)[1]
body='\n'.join([
    'CAEDRAL_DOWNLOAD_HOST='+data['hostname'],
    'CAEDRAL_RELEASE_MANIFEST=/srv/caedral-wallet-beta/release.json',
    'CAEDRAL_RELEASE_APK=/srv/caedral-wallet-beta/caedralpay-wallet-0.1.0-beta.1.apk',
    'TURNSTILE_SITE_KEY='+data['turnstileSitekey'],
    'TURNSTILE_SECRET_KEY='+data['turnstileSecret'],
    'DOWNLOAD_TOKEN_SECRET='+secret,''])
fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|getattr(os,'O_NOFOLLOW',0),0o600)
with os.fdopen(fd,'w') as stream:stream.write(body)
command='umask 077; cat > /etc/caedral-wallet/download.env.next && chmod 600 /etc/caedral-wallet/download.env.next && mv /etc/caedral-wallet/download.env.next /etc/caedral-wallet/download.env && systemctl restart caedral-wallet-download.service'
result=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','StrictHostKeyChecking=yes','servidor',command],
    input=body,text=True,capture_output=True,timeout=30)
if result.returncode:raise SystemExit('Não foi possível aplicar a configuração. Nenhuma credencial foi impressa.')
print('Widget configurado no serviço de download. O token administrativo da Cloudflare permaneceu apenas nesta máquina.')
