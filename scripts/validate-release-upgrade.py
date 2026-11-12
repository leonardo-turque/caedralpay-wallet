#!/usr/bin/env python3
"""Signed 1002 -> 1003 APK update in a disposable UID, preserving vault/Keystore/history."""
from pathlib import Path
import json,os,re,subprocess,tempfile

root=Path(__file__).resolve().parents[1]
adb=str(Path.home()/'Android/Sdk/platform-tools/adb')
package='com.caedralpay.wallet.upgradevalidation'
def call(command,**kwargs):return subprocess.run(command,check=True,**kwargs)
policy=call([adb,'shell','dumpsys','window','policy'],capture_output=True,text=True).stdout
if 'showing=true' in policy:raise SystemExit('Desbloqueie o aparelho para validar as chaves de teste.')
installed=call([adb,'shell','pm','list','packages',package],capture_output=True,text=True).stdout
if 'package:'+package in installed:raise SystemExit('Pacote de teste já existe; não apagar automaticamente estado prévio.')
env=os.environ.copy();env.setdefault('CAEDRAL_SIGNING_PROPERTIES',str(Path.home()/'.local/share/caedralpay/android-signing/signing.properties'))
evidence=[]
with tempfile.TemporaryDirectory(prefix='caedral-upgrade-') as directory:
    for version,method in [(1002,'seedPersistentState'),(1003,'verifyStateAfterSignedUpdateAndRecoverPhrase')]:
        print('Compilando release QA assinado, versionCode',version,flush=True)
        log=Path(directory)/f'build-{version}.log'
        with log.open('w') as output:
            result=subprocess.run([str(root/'gradlew'),'--offline','--no-configuration-cache',
                '-PcaedralIsolatedUiTest=true','-PcaedralReleaseValidation=true','-PcaedralUpgradeValidation=true',
                '-PcaedralVersionCode='+str(version),':app:assembleRelease',':app:assembleReleaseAndroidTest'],
                cwd=root,env=env,stdout=output,stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text()[-6000:]);raise SystemExit('Build de atualização falhou.')
        call([adb,'install','-r',str(root/'app/build/outputs/apk/release/app-release.apk')],capture_output=True)
        call([adb,'install','-r',str(root/'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')],capture_output=True)
        run=call([adb,'shell','am','instrument','-w','-e','class','com.caedralpay.wallet.ReleaseUpgradeTest#'+method,
            '-e','caedralUpgradeQa','true',package+'.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
        if 'OK (1 test)' not in run.stdout or 'FAILURES!!!' in run.stdout:print(run.stdout[-5000:]);raise SystemExit('Teste de atualização não aprovado.')
        matches=re.findall(r'INSTRUMENTATION_STATUS: caedralUpgradeResult=(\{[^\n]+\})',run.stdout)
        if len(matches)!=1:raise SystemExit('Evidência de atualização ausente.')
        data=json.loads(matches[0]);assert data['passed'] is True and data['versionCode']==version
        evidence.append(data);print('Aprovado:',data,flush=True)
target=root/'qa/results/signed-upgrade.json'
target.write_text(json.dumps({'steps':evidence,'sameSigningKey':True,'preserved':['vault','keystore-access','account-names','derived-addresses','preferences','encrypted-history'],
    'recovery':'Nova cifragem e PIN diferente recuperaram os mesmos endereços de duas contas a partir da frase pública de fixture.',
    'deviceScope':'A57 Android 17; não representa cobertura de todos os aparelhos ou biometria física.'},indent=2,ensure_ascii=False)+'\n')
print('Evidência:',target)
