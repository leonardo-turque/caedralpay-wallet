#!/usr/bin/env python3
"""Read-only cross-check of QA transactions through the second configured public RPC."""
from pathlib import Path
from decimal import Decimal
import json, subprocess

root=Path(__file__).resolve().parents[1]
data=json.loads((root/'qa/results/public-network.json').read_text())
endpoints={56:'https://bsc-dataseed-public.bnbchain.org',97:'https://bsc-testnet.bnbchain.org'}
contracts={56:'0x55d398326f99059fF775485246999027B3197955',97:'0x337610d27c682E347C9cD60BD4b3b107C9d34dDd'}
counter=0
def rpc(chain,method,*params):
    global counter
    counter+=1
    body={'jsonrpc':'2.0','id':counter,'method':method,'params':list(params)}
    result=subprocess.run(['curl','--fail','--silent','--show-error','--max-time','20',
        '-H','Content-Type: application/json','--data-binary',json.dumps(body),endpoints[chain]],
        check=True,capture_output=True,text=True,timeout=25)
    response=json.loads(result.stdout)
    assert response['id']==counter and not response.get('error') and 'result' in response
    return response['result']
verified=[]
for chain in [56,97]:
    assert int(rpc(chain,'eth_chainId'),16)==chain
    head=int(rpc(chain,'eth_blockNumber'),16)
    rows=[r for r in data['transactions'] if r['chainId']==chain]
    assert len(rows)==(4 if chain==56 else 2)
    assert all(r['status']=='Confirmed' for r in rows)
    for record in rows:
        receipt=rpc(chain,'eth_getTransactionReceipt',record['hash'])
        tx=rpc(chain,'eth_getTransactionByHash',record['hash'])
        expected_to=record['recipient'] if record['asset']=='BNB' else contracts[chain]
        assert receipt['transactionHash'].lower()==record['hash'].lower()
        assert int(receipt['status'],16)==1 and int(receipt['blockNumber'],16)==record['block']
        assert receipt['from'].lower()==record['from'].lower() and receipt['to'].lower()==expected_to.lower()
        assert tx['from'].lower()==record['from'].lower() and tx['to'].lower()==expected_to.lower()
        block=rpc(chain,'eth_getBlockByNumber',receipt['blockNumber'],False)
        assert block['hash'].lower()==receipt['blockHash'].lower()
        assert head-int(receipt['blockNumber'],16)+1>=15
        fee=int(receipt['gasUsed'],16)*int(receipt['effectiveGasPrice'],16)
        assert fee==int(record['actualFeeWei'])
        if record['asset']=='BNB':
            assert int(tx['value'],16)==int(record['units']) and tx['input']=='0x'
        else:
            assert int(tx['value'],16)==0
            calldata='0xa9059cbb'+record['recipient'][2:].lower().rjust(64,'0')+format(int(record['units']),'x').rjust(64,'0')
            assert tx['input'].lower()==calldata
            assert any(log['address'].lower()==contracts[chain].lower() and len(log['topics'])==3
                and log['topics'][0].lower()=='0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef'
                and log['topics'][1].lower()=='0x'+record['from'][2:].lower().rjust(64,'0')
                and log['topics'][2].lower()=='0x'+record['recipient'][2:].lower().rjust(64,'0')
                and int(log['data'],16)==int(record['units']) for log in receipt['logs'])
        verified.append({**record,'verificationEndpoint':endpoints[chain],
            'confirmationsAtVerification':head-record['block']+1,'canonicalBlockVerified':True,
            'explorer':('https://bscscan.com/tx/' if chain==56 else 'https://testnet.bscscan.com/tx/')+record['hash']})
balances=[]
addresses=sorted({r['from'] for r in data['transactions']})
for chain in [56,97]:
    for address in addresses:
        native=int(rpc(chain,'eth_getBalance',address,'latest'),16)
        call={'to':contracts[chain],'data':'0x70a08231'+address[2:].lower().rjust(64,'0')}
        token=int(rpc(chain,'eth_call',call,'latest'),16)
        balances.append({'chainId':chain,'address':address,'native':str(Decimal(native)/Decimal(10**18)),
            'usdt':str(Decimal(token)/Decimal(10**18))})
proof={'transactions':verified,'balancesAfterTests':balances,
    'mainnetFeeBnb':str(sum(Decimal(r['actualFeeWei']) for r in verified if r['chainId']==56)/Decimal(10**18)),
    'publicTestnetTokenTransferTested':False,
    'scope':'Ida/volta BNB e USDT mainnet; ida/volta tBNB testnet. Sem USDT de teste financiado.'}
target=root/'qa/results/public-chain-proof.json';target.write_text(json.dumps(proof,indent=2,ensure_ascii=False)+'\n')
print('Seis transações verificadas em RPC alternativo; taxas mainnet:',proof['mainnetFeeBnb'],'BNB')
