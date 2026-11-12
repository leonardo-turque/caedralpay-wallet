#!/usr/bin/env python3
"""Create fresh QA credentials outside Git; stdout includes public addresses only."""
from pathlib import Path
import json,os,secrets,subprocess

directory=Path.home()/'.local/share/caedralpay/public-network-qa'
directory.mkdir(parents=True,exist_ok=True,mode=0o700)
directory.chmod(0o700)
file=directory/'wallet.json'
if not file.exists():
    result=subprocess.run([str(Path.home()/'.foundry/bin/cast'),'wallet','new-mnemonic','--accounts','2','--json'],
        check=True,capture_output=True,text=True)
    value=json.loads(result.stdout)
    if value.get('success') is not True or len(value['data']['mnemonic'].split())!=12 or len(value['data']['accounts'])!=2:
        raise SystemExit('Formato inesperado do gerador; nenhuma credencial salva.')
    value['pin']=''.join(str(secrets.randbelow(10)) for _ in range(6))
    for account in value['data']['accounts']:account.pop('private_key',None)
    fd=os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as stream:json.dump(value,stream)
if file.stat().st_mode & 0o077:raise SystemExit('Arquivo QA precisa ter permissão 600.')
value=json.loads(file.read_text())
print('Credenciais privadas preservadas fora do Git:',file)
for index,account in enumerate(value['data']['accounts']):print('Conta QA',index,account['address'])
