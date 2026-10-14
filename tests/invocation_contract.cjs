// Cross-invocation replay and replacement ordering are the durable action contract.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto')
const {AsyncLocalStorage}=require('node:async_hooks')
const create=require('../skill/scripts/invoke.cjs')
const dataDir=fs.mkdtempSync(path.join(os.tmpdir(),'musecraft-invoke-'))
const owner={id:'test',generation:1,intent:'playing'},world={session:'world',server:'host',dimension:'overworld'}
let current=null, effects=[], cancelRelease=null,cancelEntered=null
const adapter={observe:async()=>world,releaseOwner:async()=>{},screen:{capture:async()=>({path:'test.png'})},mc:{
  jobs:{list:()=>current?[current]:[],cancel:async id=>{effects.push(['cancel',id]);if(cancelEntered)cancelEntered();if(cancelRelease)await cancelRelease;current=null}},
  follow:async id=>{effects.push(['follow',id]);current={id:'follow',status:'running'};return current},
  breakBlocks:async points=>{assert.equal(current,null);effects.push(['break',points]);current={id:'mine',status:'running'};return current},
  chat:async text=>{effects.push(['chat',text]);return {submitted:true}},
  inventory:async()=>['stone'],entities:async()=>({connected:true,entities:[{id:5}],scope:{radius:32,omitted:0}})}}
const make=()=>create({dataDir,session:()=>owner,endSession:()=>{owner.intent='ended';current=null;return owner},adapter,execution:new AsyncLocalStorage()})
const body=(seq,method,input)=>({sequence:seq,method,input,callId:'call-'+seq,playSession:owner.id,generation:owner.generation,world:{...world}})
async function main(){
 let invoke=make()
 const follow=body(1,'mc.follow',{entityId:5})
 await invoke(follow);await invoke(follow);assert.equal(effects.length,1)
 await invoke(body(2,'mc.chat',{text:'Still following'}));await invoke(body(3,'mc.inventory',{}));assert.equal(current.id,'follow')
 const entities=await invoke({...body(3,'mc.entities',{}),callId:'entity-read'})
 assert.equal(entities.entities[0].id,5);assert.equal(current.id,'follow')
 await assert.rejects(invoke(body(4,'mc.breakBlocks',{positions:'invalid'})),/Invalid/);assert.equal(current.id,'follow')
 let release,entered
 cancelRelease=new Promise(r=>release=r);const ready=new Promise(r=>entered=r);cancelEntered=entered
 const mining=invoke(body(5,'mc.breakBlocks',{positions:[[1,2,3]]}));await ready
 const read=invoke(body(6,'mc.inventory',{}))
 assert.equal(effects.filter(e=>e[0]==='break').length,0)
 release();await mining;assert.deepEqual(await read,['stone']);assert.equal(current.id,'mine')
 assert.deepEqual(effects.filter(e=>e[0]==='cancel'),[['cancel','follow']])
 const orderPath=path.join(dataDir,'invocations','action-order.json')
 const miningOrder=fs.readFileSync(orderPath,'utf8')
 await invoke(body(100,'mc.chat',{text:'Still mining'}));assert.equal(current.id,'mine')
 assert.equal(fs.readFileSync(orderPath,'utf8'),miningOrder,'chat must not advance or replace the physical-action fence')
 await assert.rejects(invoke(body(4,'mc.follow',{entityId:5})),/stale_action/)
 invoke=make();assert.equal((await invoke(follow)).status,'running');assert.equal(current.id,'mine','a replay must not resurrect replaced movement')
 await assert.rejects(invoke({...follow,input:{entityId:6}}),/input_changed/)
 await assert.rejects(invoke({...body(7,'mc.follow',{entityId:6}),world:{...world,session:'old'}}),/world_changed/)
 const uncertain=body(8,'mc.follow',{entityId:7})
 const hash=crypto.createHash('sha256').update(JSON.stringify([uncertain.method,uncertain.input,uncertain.sequence,uncertain.world])).digest('hex')
 const name=crypto.createHash('sha256').update('test:1:call-8').digest('hex')+'.json'
 fs.writeFileSync(path.join(dataDir,'invocations',name),JSON.stringify({hash}))
 assert.equal((await invoke(uncertain)).status,'uncertain');assert.equal(current.id,'mine')
 await invoke(body(9,'mc.endSession',{reason:'done'}))
 await assert.rejects(invoke(body(10,'mc.follow',{entityId:6})),/ended/)
 owner.id='next';owner.generation=2;owner.intent='playing'
 await assert.rejects(invoke(follow),/session_changed/)
 // A fresh call after completion is a new effect even within the same turn.
 // Retries of that exact invocation must still replay its durable receipt.
 let transfers=0
 adapter.mc.transfer=async()=>{transfers++;current={id:'transfer-'+transfers,status:'completed',result:{count:1}};return current}
 world.clientTick=11
 const input={item:'minecraft:iron_pickaxe',count:1,direction:'deposit'}
 const old={...world,clientTick:10,playerChatId:'request-one'}
 const firstTransfer={...body(1,'mc.transfer',input),world:old}
 await invoke(firstTransfer)
 const secondTransfer={...body(2,'mc.transfer',{direction:'deposit',count:1,item:'minecraft:iron_pickaxe'}),world:old}
 const repeated=await invoke(secondTransfer)
 assert.equal(repeated.coalesced,undefined);assert.equal(repeated.status,'completed');assert.equal(transfers,2)
 invoke=make()
 await invoke(firstTransfer);await invoke(secondTransfer)
 assert.equal(transfers,2,'retry after controller reconstruction must not repeat either effect')
 await invoke({...body(3,'mc.transfer',input),world:old})
 assert.equal(transfers,3,'new calls with the same admission frame are intentional repetitions')
 world.clientTick=12
 await invoke({...body(4,'mc.transfer',input),world:{...world,playerChatId:'request-one'}})
 assert.equal(transfers,4)
 await invoke({...body(5,'mc.transfer',input),world:{...old,playerChatId:'request-two'}})
 assert.equal(transfers,5)
 // Active repeats do not cancel movement. Missing post-restart state is uncertain.
 world.clientTick=20
 await invoke({...body(6,'mc.follow',{entityId:5}),world:{...old,playerChatId:'request-two'}})
 const effectCount=effects.length
 assert.equal((await invoke({...body(7,'mc.follow',{entityId:5}),world:{...old,playerChatId:'request-two'}})).coalesced,true)
 assert.equal(effects.length,effectCount)
 current=null;invoke=make()
 assert.equal((await invoke({...body(8,'mc.follow',{entityId:5}),world:{...old,playerChatId:'request-two'}})).status,'uncertain')
 assert.equal(effects.length,effectCount)
 // A different action still cancels/replaces work even from that older frame.
 await invoke({...body(9,'mc.follow',{entityId:6}),world:old})
 assert.equal(effects.at(-1)[1],6)
 // Appending combat must preserve the same executor.
 adapter.mc.attack=async id=>{effects.push(['attack',id]);current={id:'combat',kind:'attack',status:'running',targets:[id]};return current}
 adapter.mc.queueAttack=async id=>{if(current?.kind!=='attack')throw Error('attack_not_running');if(id===999)throw Error('target_unavailable');if(!current.targets.includes(id))current.targets.push(id);effects.push(['queue',id]);return current}
 await invoke(body(20,'mc.attack',{entityId:7}))
 const cancellations=effects.filter(e=>e[0]==='cancel').length
 const append=body(21,'mc.queueAttack',{entityId:8})
 await invoke(append);await invoke(append)
 assert.deepEqual(current.targets,[7,8]);assert.equal(effects.filter(e=>e[0]==='queue').length,1)
 assert.deepEqual(current.targets,[7,8]);assert.equal(effects.filter(e=>e[0]==='cancel').length,cancellations)
 const rejected=await invoke(body(23,'mc.queueAttack',{entityId:999}))
 assert.equal(rejected.status,'blocked');assert.equal(rejected.reason,'target_unavailable');assert.deepEqual(current.targets,[7,8])
 await assert.rejects(invoke({...body(24,'mc.queueAttack',{entityId:9}),world:{...world,session:'old'}}),/world_changed/)
 const ordinary=body(25,'mc.follow',{entityId:8})
 assert.equal((await invoke(ordinary)).reason,'defense_in_progress')
 invoke=make();assert.equal((await invoke(ordinary)).reason,'defense_in_progress')
 assert.equal(current.id,'combat');assert.deepEqual(current.targets,[7,8])
 assert.equal(effects.filter(e=>e[0]==='cancel').length,cancellations)
 current.status='completed'
 await invoke(body(26,'mc.follow',{entityId:8}))
 const busy=await invoke(body(27,'mc.queueAttack',{entityId:7}))
 assert.equal(busy.reason,'attack_not_running');assert.equal(current.id,'follow')
 assert.equal(effects.filter(e=>e[0]==='cancel').length,cancellations)
 // Flee updates retain the current action; unavailable additions cannot cancel it.
 adapter.prepareFlee=async request=>{if(request.entityId===999)throw Error('target_gone:999');return request}
 adapter.mc.flee=async request=>{
   const ids=request.entityIds??[request.entityId]
   if(current?.kind==='flee'){for(const id of ids)if(!current.targets.includes(id))current.targets.push(id)}
   else current={id:'escape',kind:'flee',status:'running',targets:ids}
   return current
 }
 const invalid=await invoke(body(30,'mc.flee',{entityId:999}))
 assert.equal(invalid.reason,'target_gone');assert.equal(invalid.entityId,999);assert.equal(current.id,'follow')
 await invoke(body(31,'mc.flee',{entityId:7}))
 const cancelledBeforeMerge=effects.filter(e=>e[0]==='cancel').length
 await invoke(body(32,'mc.flee',{entityIds:[8,9]}))
 assert.deepEqual(current.targets,[7,8,9]);assert.equal(current.id,'escape')
 await invoke(body(33,'mc.flee',{entityId:999}))
 assert.equal(current.id,'escape');assert.deepEqual(current.targets,[7,8,9])
 assert.equal(effects.filter(e=>e[0]==='cancel').length,cancelledBeforeMerge)
 assert.equal((await invoke(body(34,'mc.follow',{entityId:8}))).reason,'defense_in_progress')
 assert.equal(current.id,'escape')
 await invoke(body(35,'mc.attack',{entityId:9}));assert.equal(current.id,'combat')
 await invoke(body(36,'mc.flee',{entityId:9}));assert.equal(current.id,'escape')
 await invoke(body(37,'mc.endSession',{reason:'explicit stop'}));assert.equal(current,null);assert.equal(owner.intent,'ended')
 console.log('Passed durable replay, uncertain-effect recovery, replacement ordering, independent chat/reads, input/world/generation fences and terminal intent')
}
main().finally(()=>fs.rmSync(dataDir,{recursive:true,force:true})).catch(e=>{console.error(e);process.exitCode=1})
