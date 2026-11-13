// Creates a private QA-only operator configuration. No customer credentials are exported.
import {ethers} from 'ethers';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
const root=path.resolve(import.meta.dirname,'../..');
const provider=new ethers.JsonRpcProvider('https://bsc-testnet-dataseed.bnbchain.org',undefined,{cacheTimeout:-1,batchMaxCount:1});
try {
 if(await provider.send('eth_chainId',[])!=='0x61')throw Error('Wrong chain');
 const source=path.join(os.homedir(),'.local/share/caedralpay/public-network-qa/wallet.json');
 if((fs.statSync(source).mode&0o077)!==0)throw Error('QA file permissions');
 const fixture=JSON.parse(fs.readFileSync(source));
 const payer=ethers.HDNodeWallet.fromPhrase(fixture.data.mnemonic,undefined,"m/44'/60'/0'/0/0").connect(provider);
 if(payer.address.toLowerCase()!==fixture.data.accounts[0].address.toLowerCase())throw Error('QA identity mismatch');
 const operator=ethers.HDNodeWallet.fromPhrase(fixture.data.mnemonic,undefined,"m/44'/60'/0'/0/3");
 const proof=JSON.parse(fs.readFileSync(path.join(root,'protocol/deployments/97-recurring.json')));
 if(ethers.keccak256(await provider.getCode(proof.settlement.address))!==proof.settlement.runtimeHash)throw Error('Runtime mismatch');
 const directory=path.join(os.homedir(),'.local/share/caedralpay/recurring-keeper');fs.mkdirSync(directory,{recursive:true,mode:0o700});
 const funding=path.join(directory,'funding.json');const value=ethers.parseEther('0.001');
 let hash;
 if(fs.existsSync(funding)){
  const pending=JSON.parse(fs.readFileSync(funding));hash=pending.hash;
  if(!await provider.getTransaction(hash) && !await provider.getTransactionReceipt(hash))await provider.broadcastTransaction(pending.raw);
 }else if(await provider.getBalance(operator.address)<value){
  const gasPrice=BigInt(await provider.send('eth_gasPrice',[]))*110n/100n;const gasLimit=21000n;
  if(gasLimit*gasPrice>ethers.parseEther('0.0001') || await provider.getBalance(payer.address)<value+gasLimit*gasPrice)throw Error('QA funding budget');
  const raw=await payer.signTransaction({chainId:97,type:0,to:operator.address,value,gasLimit,gasPrice,nonce:await provider.getTransactionCount(payer.address,'pending')});hash=ethers.keccak256(raw);
  fs.writeFileSync(funding,JSON.stringify({raw,hash}),{mode:0o600});await provider.broadcastTransaction(raw);
 }
 if(hash){const receipt=await provider.waitForTransaction(hash,15,180000);if(!receipt || receipt.status!==1)throw Error('Funding not confirmed');}
 const config={chainId:97,rpc:'https://bsc-testnet-rpc.publicnode.com',settlement:proof.settlement.address,runtimeHash:proof.settlement.runtimeHash,startBlock:proof.settlement.blockNumber,budgetWei:value.toString(),operatorKey:operator.privateKey};
 fs.writeFileSync(path.join(directory,'config.json'),JSON.stringify(config),{mode:0o600});
 const publicProof={chainId:97,operator:operator.address,fundingHash:hash || null,budgetWei:value.toString(),customerKeysRequired:false};
 fs.writeFileSync(path.join(root,'qa/results/recurring-keeper.json'),JSON.stringify(publicProof,null,2)+'\n');console.log(JSON.stringify(publicProof));
}catch(error){console.error('QA keeper setup stopped:',error.code || error.name);process.exitCode=1;}finally{provider.destroy();}
