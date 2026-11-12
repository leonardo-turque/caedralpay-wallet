#!/usr/bin/env python3
"""Opt-in public-network QA on the isolated Android debug package; never prints secrets."""
from pathlib import Path
import argparse, json, os, subprocess, xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['read', 'capacity', 'recovery', 'send'])
parser.add_argument('--chain', type=int, choices=[56, 97])
parser.add_argument('--confirm-public-spend', action='store_true')
parser.add_argument('--native-only', action='store_true')
args = parser.parse_args()
if args.mode == 'send' and (not args.chain or not args.confirm_public_spend):
    raise SystemExit('Envio exige --chain e --confirm-public-spend; usa somente contas QA e orçamento limitado.')
root = Path(__file__).resolve().parents[1]
adb = str(Path.home()/'Android/Sdk/platform-tools/adb')
package = 'com.caedralpay.wallet.validation'
def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)
policy = run([adb, 'shell', 'dumpsys', 'window', 'policy'], capture_output=True, text=True).stdout
if 'showing=true' in policy: raise SystemExit('Desbloqueie o aparelho para usar as chaves QA do Android Keystore.')
source = Path.home()/'.local/share/caedralpay/public-network-qa/wallet.json'
if source.stat().st_mode & 0o077: raise SystemExit('O arquivo privado QA precisa ter permissão 600.')
private = json.loads(source.read_text())
data = private['data']
fixture = {'mnemonic': data['mnemonic'], 'pin': private['pin'], 'addresses': [a['address'] for a in data['accounts']]}
run([adb, 'shell', 'run-as', package, 'mkdir', '-p', 'files'], capture_output=True)
run([adb, 'shell', f"run-as {package} sh -c 'umask 077; cat > files/public-network-fixture.json'"],
    input=json.dumps(fixture).encode(), capture_output=True)
methods = {'read':'readPublicNetworks', 'capacity':'concurrentReadCapacity',
           'recovery':'verifyRecoveryAndExistingVault', 'send':'sendAndReturnNativeAndToken'}
command = [str(root/'gradlew'), '--offline', '--no-configuration-cache', '-PcaedralIsolatedUiTest=true',
    '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
    '-Pandroid.testInstrumentationRunnerArguments.caedralPublicEvm=true',
    '-Pandroid.testInstrumentationRunnerArguments.class=com.caedralpay.wallet.PublicNetworkValidationTest#'+methods[args.mode]]
if args.chain: command += ['-Pandroid.testInstrumentationRunnerArguments.caedralPublicChain='+str(args.chain)]
if args.native_only: command += ['-Pandroid.testInstrumentationRunnerArguments.caedralPublicAssets=native']
command += [':app:connectedDebugAndroidTest']
try:
    run(command, cwd=root)
    paths=list((root/'app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml'))
    if not paths: raise SystemExit('Sem relatório de testes.')
    count=0
    for file in paths:
        result=ET.parse(file).getroot()
        if any(int(result.get(k,0)) for k in ['failures','errors','skipped']): raise SystemExit('Teste público não aprovado; consulte XMLs.')
        cases=list(result.iter('testcase'));count+=len(cases)
        if any(case.get('classname')!='com.caedralpay.wallet.PublicNetworkValidationTest' or case.get('name')!=methods[args.mode] for case in cases):
            raise SystemExit('Suíte diferente da solicitada.')
    if count != 1: raise SystemExit('Esperado exatamente um teste explícito.')
    public=run([adb, 'shell', 'run-as', package, 'cat', 'files/public-network-report.json'],capture_output=True,text=True).stdout
    report=json.loads(public)
    forbidden=['mnemonic','pin','private_key','raw']
    def inspect(value):
        if isinstance(value,dict):
            if any(key in forbidden for key in value): raise SystemExit('Campo privado no relatório: exportação recusada.')
            for item in value.values():inspect(item)
        elif isinstance(value,list):
            for item in value:inspect(item)
    inspect(report)
    target=root/'qa/results/public-network.json';target.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    print('Evidência pública registrada:', target)
finally:
    subprocess.run([adb,'shell','run-as',package,'rm','-f','files/public-network-fixture.json'],capture_output=True)
