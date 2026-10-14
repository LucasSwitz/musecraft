// One danger decision per encounter survives delivery, tool races and controller restart.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path')
const {AsyncLocalStorage}=require('node:async_hooks')
const create=require('../skill/scripts/event-inbox.cjs'),createInvoke=require('../skill/scripts/invoke.cjs')
const {project,offer}=require('../skill/scripts/turn-context.cjs')
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'musecraft-threat-'))
let clock=10000, inbox, invocation=0, current=null, fail=false
const owner={id:'threat-play',generation:1,intent:'playing'}
const args={dataDir:dir,session:()=>owner,now:()=>clock}
const mob={id:42,uuid:'zombie',type:'minecraft:zombie',alive:true,lineOfSight:true,inFieldOfView:true,pos:[0,64,5],distance:5}
const frame=(entities=[],sequence=0,health=20)=>({session:'world',server:'host',dimension:'overworld',
  connection:{playSession:owner.id},connected:true,ready:true,health,
  observedAt:new Date(clock).toISOString(),damage:{sequence,attacker:{...mob,uuid:'42'},observedAt:new Date(clock).toISOString()},
  visibleHostiles:{range:64,fieldOfViewDegrees:120,entities,omitted:0}})
const danger=(level,id=42)=>({...mob,id,uuid:String(id),threat:{level,evidence:['dangerous_proximity']}})
const restart=()=>{inbox=create(args);return createInvoke({dataDir:dir,session:()=>owner,inbox,
  execution:new AsyncLocalStorage(),adapter:{prepareFlee:async request=>request,observe:async()=>({session:'world',server:'host',dimension:'overworld'}),
    releaseOwner:async()=>{},mc:{jobs:{list:()=>current?[current]:[],cancel:async()=>{current=null}},
    inventory:async()=>[],chat:async()=>({submitted:true}),closeScreen:async()=>({status:'completed'}),
    flee:async()=>{if(fail)throw Error('not_ready');return current={id:'escape-'+invocation,status:'running',kind:'flee',playSession:owner.id}}}}})}
const inspect=()=>JSON.parse(fs.readFileSync(path.join(dir,'observation-inbox.json'))).threatResponse
const next=async()=>{clock+=201;return (await inbox.next(owner.id,0)).batch}
const present=()=>project(inbox.current(owner.id,true)).world.identity
const call=(method,input,world)=>({method,input,world,callId:'call-'+(++invocation),sequence:invocation,playSession:owner.id,generation:owner.generation})
;(async()=>{
 let invoke=restart()
 inbox.capture(frame());let batch=await next();inbox.ack(owner.id,batch.id);present()
 inbox.capture(frame([{...mob,lineOfSight:false}]));assert.equal(await next(),null)
 inbox.capture(frame([mob]));batch=await next();assert.equal(batch.priority,'normal');inbox.ack(owner.id,batch.id);present()
 for(let i=0;i<50;i++){inbox.capture(frame([mob]));assert.equal(await next(),null)}
 inbox.capture(frame([danger(2)]));batch=await next()
 assert.equal(batch.priority,'urgent')
 // The offered batch and the pending decision survive a lost ACK and restart.
 invoke=restart();assert.deepEqual((await inbox.next(owner.id,0)).batch,batch)
 const sent=offer(batch),seen=sent.context.identity
 assert.equal(batch.generation,owner.generation)
 assert.deepEqual(sent.context.state.threatResponse.receipt,seen.threatResponse)
 // Newer danger does not change the frozen receipt of the offered batch.
 inbox.capture(frame([danger(3)],1,19))
 assert.deepEqual(offer((await inbox.next(owner.id,0)).batch).context,sent.context)
 inbox.ack(owner.id,batch.id);assert.equal(inspect().awaiting,true)
 inbox.capture(frame([danger(3),danger(2,43)],1,18));batch=await next()
 assert.equal(batch,null,'delivery ACK must not queue another turn while the danger decision is pending')
 // More damage and additional mobs update the same state across restart.
 inbox.capture(frame([danger(3),danger(3,43),danger(2,44)],2,5));assert.equal(await next(),null)
 invoke=restart();inbox.capture(frame([danger(3),danger(3,43),danger(2,44)],2,5));assert.equal(await next(),null)
 const report=await invoke(call('mc.inventory',{},seen))
 assert.equal(report.threatUpdate.health,5);assert.equal(report.threatUpdate.entities.length,3)
 assert.equal(report.threatUpdate.damage.hits,2)
 await invoke(call('mc.chat',{text:'Running'},seen))
 fail=true;const blocked=await invoke(call('mc.flee',{entityId:42},seen));fail=false
 assert.equal(blocked.status,'blocked')
 await invoke(call('mc.flee',{entityId:42},seen))
 assert.equal(inspect().awaiting,true,'the encounter stays one decision while the player acts')
 inbox.capture(frame([danger(3),danger(3,43),danger(2,46)],2,5))
 assert.equal(await next(),null,'a follow-up escalation during the encounter stays coalesced')
 // Ten seconds without visible danger or damage clears the encounter. The next
 // hit, once the escape has ended, is a new urgent decision.
 inbox.capture(frame([],2,20));clock+=10001;inbox.capture(frame([],2,20))
 assert.equal(inspect().awaiting,false);assert.equal(inspect().hits,0)
 current=null;inbox.updateJobs([])
 const quiet=await next();if(quiet)inbox.ack(owner.id,quiet.id)
 inbox.capture(frame([],3,18));batch=await next()
 assert.equal(batch.priority,'urgent');assert.ok(batch.events.some(e=>e.kind==='damaged'))
 assert.equal(inspect().audit.filter(e=>e.kind==='decision_requested').length,2)
 invoke=restart();assert.equal(inspect().awaiting,true)
 console.log('PASS single urgent decision per encounter, coalesced follow-ups, fresh threat state in results, lost ACK/restart, encounter clear and rearm')
})().finally(()=>fs.rmSync(dir,{recursive:true,force:true})).catch(e=>{console.error(e);process.exitCode=1})
