// Uses only the separately funded QA wallet, never the user's wallet or a mainnet RPC.
import {ethers} from 'ethers';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
const root=path.resolve(import.meta.dirname,'../..');
const provider=new ethers.JsonRpcProvider('https://bsc-testnet-dataseed.bnbchain.org',undefined,{cacheTimeout:-1,batchMaxCount:1});
const directory=path.join(os.homedir(),'.local/share/caedralpay/recurring-deployment');
fs.mkdirSync(directory,{recursive:true,mode:0o700});
try {
 if(await provider.send('eth_chainId',[])!=='0x61')throw Error('Wrong chain');
 const file=path.join(os.homedir(),'.local/share/caedralpay/public-network-qa/wallet.json');
 if((fs.statSync(file).mode&0o077)!==0)throw Error('Private QA fixture permissions');
 const fixture=JSON.parse(fs.readFileSync(file));
 const payer=ethers.HDNodeWallet.fromPhrase(fixture.data.mnemonic,undefined,"m/44'/60'/0'/0/0").connect(provider);
 if(payer.address.toLowerCase()!==fixture.data.accounts[0].address.toLowerCase())throw Error('QA identity mismatch');
 const treasury=ethers.HDNodeWallet.fromPhrase(fixture.data.mnemonic,undefined,"m/44'/60'/0'/0/2").address;
 const token='0x337610d27c682E347C9cD60BD4b3b107C9d34dDd';
 async function deploy(name,args){
  const artifact=JSON.parse(fs.readFileSync(path.join(root,`protocol/out/CaedralRecurringPayments.sol/${name}.json`)));
  const stateFile=path.join(directory,name+'.json');
  let pending;
  if(fs.existsSync(stateFile))pending=JSON.parse(fs.readFileSync(stateFile));
  else{
   const draft=await new ethers.ContractFactory(artifact.abi,artifact.bytecode.object,payer).getDeployTransaction(...args);
   const gasLimit=(await provider.estimateGas({...draft,from:payer.address}))*120n/100n;
   const gasPrice=BigInt(await provider.send('eth_gasPrice',[]))*110n/100n;
   if(gasLimit*gasPrice>ethers.parseEther('0.003') || await provider.getBalance(payer.address)<gasLimit*gasPrice)throw Error('QA gas budget');
   const nonce=await provider.getTransactionCount(payer.address,'pending');
   const raw=await payer.signTransaction({...draft,chainId:97,type:0,nonce,gasPrice,gasLimit,value:0});pending={hash:ethers.keccak256(raw),raw};
   fs.writeFileSync(stateFile,JSON.stringify(pending),{mode:0o600});
  }
  if(!await provider.getTransaction(pending.hash) && !await provider.getTransactionReceipt(pending.hash))await provider.broadcastTransaction(pending.raw);
  const receipt=await provider.waitForTransaction(pending.hash,15,180000);
  if(!receipt || receipt.status!==1 || !receipt.contractAddress)throw Error('Deployment not confirmed');
  if((await provider.getBlock(receipt.blockNumber)).hash!==receipt.blockHash)throw Error('Deployment reorg');
  const code=await provider.getCode(receipt.contractAddress);
  const normalize=hex=>{const b=Buffer.from(hex.replace(/^0x/,''),'hex');for(const ranges of Object.values(artifact.deployedBytecode.immutableReferences||{}))for(const r of ranges)b.fill(0,r.start,r.start+r.length);return b.toString('hex')};
  if(normalize(code)!==normalize(artifact.deployedBytecode.object))throw Error('Compiled runtime mismatch');
  const contract=new ethers.Contract(receipt.contractAddress,artifact.abi,provider);
  if((await contract.stableToken()).toLowerCase()!==token.toLowerCase())throw Error('Stable mismatch');
  const linked=name==='CaedralRecurringPayments'?await contract.treasury():await contract.settlement();
  if(linked.toLowerCase()!==args[0].toLowerCase())throw Error('Constructor mismatch');
  return {address:receipt.contractAddress,runtimeHash:ethers.keccak256(code),transactionHash:receipt.hash,blockNumber:receipt.blockNumber,blockHash:receipt.blockHash,gasUsed:receipt.gasUsed.toString(),feeWei:receipt.fee.toString(),confirmations:15,compiledRuntimeVerified:true};
 }
 const settlement=await deploy('CaedralRecurringPayments',[treasury,token]);
 const account=await deploy('CaedralRecurringAccount',[settlement.address,token]);
 const proof={chainId:97,treasury,token,settlement,account,compiler:'0.8.28',optimizerRuns:200};
 fs.writeFileSync(path.join(root,'protocol/deployments/97-recurring.json'),JSON.stringify(proof,null,2)+'\n');
 console.log(JSON.stringify(proof));
} catch(error){console.error('Testnet deployment stopped:',error.code || error.name);process.exitCode=1;}finally{provider.destroy();}
