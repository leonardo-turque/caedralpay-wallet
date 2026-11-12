#!/usr/bin/env python3
"""Package a signed candidate; public beta packaging fails unless every gate has evidence."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--publishable', action='store_true')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
readiness=json.loads((root/'distribution/beta-readiness.json').read_text())
missing=[name for name, gate in readiness['gates'].items() if gate['status'] != 'passed' or not gate['evidence'].strip()]
if args.publishable and (missing or readiness['betaApproved'] is not True):
    raise SystemExit('Distribuição bloqueada. Critérios pendentes: '+', '.join(missing))
apk=root/'app/build/outputs/apk/release/app-release.apk'
metadata=json.loads((apk.parent/'output-metadata.json').read_text())
if metadata['applicationId'] != 'com.caedralpay.wallet': raise SystemExit('Pacote de validação não pode ser distribuído.')
element=metadata['elements'][0]
if element['versionName'] != readiness['version']: raise SystemExit('Versões divergentes.')
sdk=Path.home()/'Android/Sdk/build-tools/36.0.0'
verification=subprocess.run([str(sdk/'apksigner'),'verify','--verbose','--print-certs',str(apk)],check=True,capture_output=True,text=True).stdout
if 'CN=Android Debug' in verification: raise SystemExit('Assinatura de desenvolvimento não permitida.')
badging=subprocess.run([str(sdk/'aapt2'),'dump','badging',str(apk)],check=True,capture_output=True,text=True).stdout
if 'application-debuggable' in badging: raise SystemExit('APK depurável não permitido.')
out=root/'dist'/('beta' if args.publishable else 'candidate')
out.mkdir(parents=True,exist_ok=True)
target=out/f'caedralpay-wallet-{readiness["version"]}.apk'
shutil.copyfile(apk,target)
digest=hashlib.sha256(target.read_bytes()).hexdigest()
fingerprint=next(line.split(': ',1)[1] for line in verification.splitlines() if 'certificate SHA-256 digest:' in line)
manifest={'version':element['versionName'],'versionCode':element['versionCode'],'applicationId':metadata['applicationId'],
          'sha256':digest,'certificateSha256':fingerprint,'file':target.name,'minAndroid':17,'betaApproved':args.publishable}
(out/'release.json').write_text(json.dumps(manifest,indent=2)+'\n')
(out/(target.name+'.sha256')).write_text(digest+'  '+target.name+'\n')
(out/'signature-verification.txt').write_text(verification)
print('Artefato '+('publicável' if args.publishable else 'candidato — distribuição bloqueada')+': '+str(target))
