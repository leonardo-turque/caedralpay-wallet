import { ethers } from 'ethers';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const ABI = [
 'event Authorized(bytes32 indexed offerId,bytes32 indexed id,address indexed payer,address merchant,address token,uint256 gross,uint64 intervalDays,uint64 endAt,uint64 cardExpiry)',
 'function subscriptions(bytes32) view returns(address payer,address merchant,address token,uint256 gross,uint64 intervalDays,uint64 endAt,uint64 startedAt,uint64 cardExpiry,uint64 lastPeriod,bool cancelled)',
 'function collect(bytes32 id)'
];
function privateJson(file) {
 const stat=fs.statSync(file);if(!stat.isFile() || (stat.mode&0o077)!==0)throw Error('Private file must have mode 600');
 return JSON.parse(fs.readFileSync(file,'utf8'));
}
export async function tick(config,stateFile) {
 const lock=stateFile+'.lock';
 if(fs.existsSync(lock)){
  const pidFile=path.join(lock,'pid');
  try{const pid=Number(fs.readFileSync(pidFile,'utf8'));if(!Number.isInteger(pid) || pid<=0)throw Error('Invalid lock');process.kill(pid,0);return {busy:true};}
  catch(error){if(error.code==='ESRCH')fs.rmSync(lock,{recursive:true});else if(error.code==='ENOENT' && Date.now()-fs.statSync(lock).mtimeMs>60000)fs.rmSync(lock,{recursive:true});else return {busy:true};}
 }
 try{fs.mkdirSync(lock,{mode:0o700});}catch(error){if(error.code==='EEXIST')return {busy:true};throw error;}
 fs.writeFileSync(path.join(lock,'pid'),String(process.pid),{mode:0o600});
 try{return await execute(config,stateFile);}finally{fs.rmSync(lock,{recursive:true});}
}
async function execute(config, stateFile) {
 if(config.chainId!==97)throw Error('Only validated BSC testnet deployment is enabled');
 const provider=new ethers.JsonRpcProvider(config.rpc,undefined,{cacheTimeout:-1,batchMaxCount:1});
 try {
  if(BigInt(await provider.send('eth_chainId',[]))!==97n)throw Error('Wrong chain');
  if(ethers.keccak256(await provider.getCode(config.settlement))!==config.runtimeHash)throw Error('Runtime mismatch');
  const identity=`${config.chainId}:${config.settlement.toLowerCase()}:${config.runtimeHash}`;
  const state=fs.existsSync(stateFile)?privateJson(stateFile):{identity,cursor:config.startBlock,ids:[],spent:'0',pending:null};
  if(state.identity!==identity)throw Error('State belongs to another deployment');
  if(!Number.isSafeInteger(state.cursor) || state.cursor<config.startBlock)throw Error('Invalid cursor');
  const write=()=>{const temp=stateFile+'.tmp';fs.writeFileSync(temp,JSON.stringify(state),{mode:0o600});fs.renameSync(temp,stateFile)};
  const operator=new ethers.Wallet(config.operatorKey,provider);
  const contract=new ethers.Contract(config.settlement,ABI,operator);
  const head=await provider.getBlock('latest');const final=head.number-14;
  if(state.pending){
   const p=state.pending;const receipt=await provider.getTransactionReceipt(p.hash);
   if(!receipt){if(!await provider.getTransaction(p.hash))await provider.broadcastTransaction(p.raw);return {pending:true};}
   if(head.number<receipt.blockNumber+14)return {pending:true};
   if((await provider.getBlock(receipt.blockNumber)).hash!==receipt.blockHash)throw Error('Receipt reorg');
   state.spent=(BigInt(state.spent)+receipt.fee).toString();state.pending=null;write();
  }
  if(state.cursor<=final){
   const end=Math.min(final,state.cursor+199);
   for(const event of await contract.queryFilter(contract.filters.Authorized(),state.cursor,end)){
    if(!state.ids.includes(event.args.id))state.ids.push(event.args.id);
   }
   state.cursor=end+1;write();
  }
  for(const id of state.ids){
   const s=await contract.subscriptions(id);const now=BigInt(head.timestamp);
   if(s.payer===ethers.ZeroAddress || s.intervalDays===0n || s.cancelled || now>=s.cardExpiry || (s.endAt!==0n && now>s.endAt))continue;
   const period=(now-s.startedAt)/(s.intervalDays*86400n);if(period<=s.lastPeriod)continue;
   // Simulation rejects insufficient funds and cancelled/changed subscriptions before gas is spent.
   let estimate;try{estimate=await contract.collect.estimateGas(id);}catch{continue;}
   const gasLimit=(estimate*120n+99n)/100n;
   const gasPrice=BigInt(await provider.send('eth_gasPrice',[]))*110n/100n;
   const maximum=gasLimit*gasPrice;
   if(gasLimit>1500000n || gasPrice>ethers.parseUnits('10','gwei'))throw Error('Gas cap exceeded');
   if(BigInt(state.spent)+maximum>BigInt(config.budgetWei))throw Error('Operator budget exhausted');
   if(await provider.getBalance(operator.address)<maximum)throw Error('Operator gas balance insufficient');
   const nonce=await provider.getTransactionCount(operator.address,'pending');
   const raw=await operator.signTransaction({chainId:97,type:0,to:config.settlement,data:contract.interface.encodeFunctionData('collect',[id]),gasLimit,gasPrice,nonce,value:0});
   const hash=ethers.keccak256(raw);state.pending={hash,raw,id};write();
   await provider.broadcastTransaction(raw);return {submitted:hash};
  }
  return {idle:true,tracked:state.ids.length};
 } finally {provider.destroy();}
}
async function main(){
 const configFile=process.env.CAEDRAL_KEEPER_CONFIG;if(!configFile)throw Error('Set CAEDRAL_KEEPER_CONFIG to a private JSON file');
 const directory=path.dirname(configFile);const state=path.join(directory,'keeper-state.json');
 do {try {console.log(JSON.stringify(await tick(privateJson(configFile),state)));}catch(error){console.error('Keeper stopped this cycle:',error.code || error.name);if(process.argv.includes('--once'))throw error;}
 if(process.argv.includes('--once'))break;await new Promise(r=>setTimeout(r,15000));}while(true);
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(()=>{process.exitCode=1});
