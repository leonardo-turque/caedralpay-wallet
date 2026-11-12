#!/usr/bin/env python3
"""Record only machine-verified test gates. External review gates are never auto-approved."""
from pathlib import Path
import argparse, json, xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('kind', choices=['unit','device','local-evm'])
parser.add_argument('--variant', choices=['debug','release'], default='debug')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
if args.kind=='unit' and args.variant!='debug': raise SystemExit('Relatório JVM suportado: debug.')
paths=list((root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')) if args.kind=='unit' else list((root/f'app/build/outputs/androidTest-results/connected/{args.variant}').glob('TEST-*.xml'))
if not paths: raise SystemExit('Relatórios ausentes.')
counts=dict(tests=0,failures=0,errors=0,skipped=0)
classes=[]
for file in paths:
    result=ET.parse(file).getroot()
    for key in counts: counts[key]+=int(result.get(key,0))
    classes.extend(test.get('classname','') for test in result.iter('testcase'))
if not counts['tests'] or any(counts[key] for key in ['failures','errors','skipped']): raise SystemExit('Critério não aprovado: '+str(counts))
if args.kind=='local-evm' and (not classes or any('EvmEndToEndTest' not in name for name in classes)):
    raise SystemExit('Relatório não corresponde à suíte EVM local.')
if args.kind=='device' and (not classes or any('EvmEndToEndTest' in name for name in classes)):
    raise SystemExit('Executar regressão sem a suíte EVM opcional.')
out=root/'qa/results';out.mkdir(parents=True,exist_ok=True)
suffix='-release' if args.variant=='release' else ''
file=out/(args.kind+suffix+'.json');file.write_text(json.dumps(counts,indent=2)+'\n')
gate={'unit':'automatedUnitTests','device':'deviceRegressionTests','local-evm':'localEvmEndToEnd'}[args.kind]
path=root/'distribution/beta-readiness.json';readiness=json.loads(path.read_text())
readiness['gates'][gate]={'status':'passed','evidence':str(file.relative_to(root))+'; '+str(counts['tests'])+' testes sem falhas/ignorados.'}
path.write_text(json.dumps(readiness,indent=2,ensure_ascii=False)+'\n')
print(gate, counts)
