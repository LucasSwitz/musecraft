// Durable event delivery, coalesced state, session fencing and quiet recovery.
const assert = require('node:assert/strict')
const fs = require('node:fs'), os = require('node:os'), path = require('node:path')
const create = require('../skill/scripts/event-inbox.cjs')
const createInvoke = require('../skill/scripts/invoke.cjs')
const {AsyncLocalStorage} = require('node:async_hooks')
const {project,context,offer} = require('../skill/scripts/turn-context.cjs')
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(),'musecraft-inbox-'))
let epoch='client:1'
let clock=10000, owner={id:'play-one',generation:1,intent:'playing',owner:'owner_name'}
const args={dataDir,session:()=>owner,now:()=>clock}
const chat=(seq,text='hello')=>({id:'chat:'+seq,seq,text,kind:'player',self:false})
const frame=(x=0,messages=[])=>({session:epoch,observedAt:new Date(clock).toISOString(),
  connection:{playSession:owner.id},connected:true,ready:true,player:'bot',pos:[x,64,0],health:20,food:20,
  inventory:[],entities:[],nearbyBlocks:{blocks:[{name:'minecraft:yellow_bed',pos:[x,64,1]}]},
  chat:{messages,nextCursor:{session:epoch,seq:messages.at(-1)?.seq||0},oldestSeq:1}})
async function run() {
  let inbox=create(args)
  const jobs=(rows,interrupted=false)=>inbox.updateJobs(rows.map(r=>({playSession:owner.id,...r})),interrupted)
  const next=async()=>{clock+=201;return (await inbox.next(owner.id,0)).batch}
  const acknowledge=async()=>{const b=await next();assert(b);inbox.ack(owner.id,b.id);return b}
  // First readiness starts one decision; arbitrary world changes stay quiet.
  inbox.capture({...frame(),connected:false,ready:false})
  assert.equal(await next(),null)
  inbox.capture(frame());const initial=await next();assert.equal(initial.events[0].kind,'ready')
  inbox=create(args);assert.equal((await inbox.next(owner.id,0)).batch.id,initial.id)
  inbox.ack(owner.id,initial.id);inbox.capture(frame());assert.equal(await next(),null)
  // Packet history preserves a short join/leave between samples, queues behind
  // an offered batch, and replays exactly once through controller recovery.
  const presence={session:'network-one',sequence:0,oldestSequence:1,events:[],
    players:[{uuid:'bot-id',name:'bot'}],count:1,omitted:0}
  inbox.capture({...frame(),playerPresence:presence});assert.equal(await next(),null)
  const visitor={uuid:'visitor-id',name:'visitor'}
  const joined={sequence:1,kind:'player_joined',player:visitor,observedAt:new Date(clock).toISOString()}
  const online={...presence,sequence:1,events:[joined],players:[...presence.players,visitor],count:2}
  inbox.capture({...frame(),playerPresence:online});const arrival=await next()
  assert.equal(arrival.priority,'normal');assert.deepEqual(arrival.events.map(e=>e.kind),['player_joined'])
  assert.deepEqual(offer(arrival).context.state.onlinePlayers.players,online.players)
  assert.equal(offer(arrival).context.state.owner,'owner_name')
  assert.match(offer(arrival).context.message,/Owner: "owner_name"/)
  const left={...joined,sequence:2,kind:'player_left'}
  const offline={...presence,sequence:2,events:[joined,left]}
  inbox.capture({...frame(),playerPresence:offline});inbox=create(args)
  assert.deepEqual((await inbox.next(owner.id,0)).batch,arrival)
  inbox.ack(owner.id,arrival.id);inbox.capture({...frame(),playerPresence:offline});const departure=await acknowledge()
  assert.deepEqual(departure.events.map(e=>[e.kind,e.player]),[['player_left',visitor]])
  inbox.capture({...frame(),playerPresence:offline});assert.equal(await next(),null)
  inbox=create(args);inbox.capture({...frame(),playerPresence:offline});assert.equal(await next(),null)
  // Dimension changes do not reset the network cursor; fresh connections adopt
  // the initial roster without manufacturing arrivals for everyone online.
  inbox.capture({...frame(),dimension:'minecraft:the_nether',playerPresence:offline});assert.equal(await next(),null)
  inbox.capture({...frame(),playerPresence:{...online,session:'network-two',sequence:0,events:[]}});assert.equal(await next(),null)
  const burst=[joined,left].map(e=>({...e,observedAt:new Date(clock).toISOString()}))
  inbox.capture({...frame(),playerPresence:{...presence,session:'network-two',sequence:2,events:burst}})
  const transient=await acknowledge();assert.deepEqual(transient.events.map(e=>e.kind),['player_joined','player_left'])
  for(let i=0;i<240;i++) {
    clock+=250
    inbox.capture({...frame(i),clientTick:i,food:i%20,air:i%300,submerged:i%2===0,
      inventory:[{name:'minecraft:stone',count:i}],screen:i%2?'Inventory':null,
      entities:[{id:1,uuid:'player',type:'minecraft:player',distance:i%10,alive:true},
        {id:2,uuid:'enemy',type:'minecraft:zombie',distance:2,lineOfSight:true,health:i%20,alive:true}]})
    assert.equal((await inbox.next(owner.id,0)).batch,null)
  }
  inbox.disconnected('stream_disconnected');assert.equal(await next(),null)
  epoch='client:2';inbox.capture(frame(240))
  const ready=await next();assert.equal(ready.events[0].kind,'ready_again')
  assert.equal(ready.priority,'normal','reconnection is not a danger decision')
  assert.equal(ready.events[0].previousClientEpoch,'client:1')
  inbox=create(args);assert.equal((await inbox.next(owner.id,0)).batch.id,ready.id)
  inbox.ack(owner.id,ready.id);inbox.capture(frame(240));assert.equal(await next(),null)
  // A container/menu transition is not a reconnect.
  inbox.capture({...frame(240),ready:false,screen:'Inventory'})
  inbox.capture(frame(240));assert.equal(await next(),null)
  // Death and respawn keep the same client epoch. Persist the pending death
  // across restart, then deliver once after terrain is loaded and input works.
  const death={...frame(240),clientTick:300,dead:true,health:0,ready:false,screen:'You Died!',dimension:'minecraft:overworld'}
  const oldIdentity=project(inbox.current(owner.id)).world.identity
  inbox.capture(death)
  const damage=await next();assert.deepEqual(damage.events.map(e=>e.kind),['died','damaged'])
  assert.equal(damage.priority,'urgent','the fatal hit is a danger decision');inbox.ack(owner.id,damage.id)
  inbox=create(args);inbox.capture(death)
  const recovered={...frame(12),clientTick:310,dead:false,dimension:'minecraft:overworld'}
  inbox.capture({...recovered,joining:true,ready:false})
  assert.equal(await next(),null)
  inbox.capture({...recovered,ready:false,screen:'Inventory'})
  assert.equal(await next(),null)
  inbox.capture(recovered);const respawn=await next()
  assert.deepEqual(respawn.events.map(e=>e.kind),['respawned'])
  assert.deepEqual(respawn.events.find(e=>e.kind==='respawned').death,
    {observedAt:death.observedAt,pos:death.pos,dimension:death.dimension})
  assert.equal(offer(respawn).context.state.player.health,20)
  assert.deepEqual(offer(respawn).context.state.player.pos,[12,64,0])
  const offeredRespawn=offer(respawn)
  assert.equal(offeredRespawn.context.identity.life,oldIdentity.life+1)
  assert.deepEqual(project(respawn).events,offeredRespawn.events)
  assert.equal(offeredRespawn.context.state.connection.ready,true)
  assert.equal(offeredRespawn.context.state.player.dead,false)
  assert.deepEqual(offeredRespawn.context.state.inventory.items,[])
  inbox=create(args);assert.deepEqual((await inbox.next(owner.id,0)).batch,respawn)
  // Lost ACK/restart reoffers the same batch with its post-respawn identity.
  assert.deepEqual(offer((await inbox.next(owner.id,0)).batch).context,offeredRespawn.context)
  inbox.ack(owner.id,respawn.id)
  inbox.capture(recovered);assert.equal(await next(),null)
  inbox=create(args);inbox.capture(recovered);assert.equal(await next(),null)
  let effects=0,callSequence=0
  const invoke=createInvoke({dataDir,session:()=>owner,inbox,execution:new AsyncLocalStorage(),
    adapter:{observe:async()=>({...recovered,session:epoch,server:null}),releaseOwner:async()=>{},
      mc:{jobs:{list:()=>[]},inventory:async()=>[],goto:async()=>{effects++;return {status:'completed'}}}}})
  const call=(world,method='mc.goto')=>({world,method,input:method==='mc.goto'?{target:[12,64,0]}:{},
    sequence:++callSequence,callId:'life-'+callSequence,playSession:owner.id,generation:owner.generation})
  // Same epoch and dimension isolate the life fence. Fresh read results do
  // not authorize an old decision; only the new turn's identity permits it.
  const stale={...offeredRespawn.context.identity,life:oldIdentity.life}
  await assert.rejects(invoke(call(stale)),/observed_life_changed/)
  await invoke(call(stale,'mc.inventory'))
  await assert.rejects(invoke(call(stale)),/observed_life_changed/)
  assert.equal(effects,0)
  const freshCall=call(project(inbox.current(owner.id)).world.identity)
  assert.equal((await invoke(freshCall)).status,'completed');assert.equal(effects,1)
  await invoke(freshCall);assert.equal(effects,1,'a replay must reuse the original effect receipt')
  // Morning resumes an idle sleeper once awake, including a sleep-skipped
  // night. The pending boundary and offered batch survive separate restarts.
  const daylight=(day,tick,extra={})=>({...recovered,server:'test:25565',worldDay:day,
    timeOfDay:tick,sleeping:false,...extra})
  inbox.capture(daylight(10,6000));assert.equal(await next(),null)
  inbox.capture(daylight(10,15000,{sleeping:true,ready:false,screen:'Chat screen'}))
  inbox.capture(daylight(11,0,{sleeping:true,ready:false,screen:'Chat screen'}))
  assert.equal(await next(),null)
  inbox=create(args)
  inbox.capture(daylight(11,1,{sleeping:true,ready:false,screen:'Chat screen'}))
  assert.equal(await next(),null)
  inbox.capture(daylight(11,2,{ready:false,screen:'Chat screen'}));assert.equal(await next(),null)
  inbox.capture(daylight(11,3));const morning=await next()
  assert.deepEqual(morning.events.map(e=>[e.kind,e.day]),[['morning',11]])
  assert.equal(morning.priority,'normal')
  const morningState=offer(morning).context.state
  assert.equal(morningState.player.sleeping,false);assert.equal(morningState.connection.ready,true)
  inbox=create(args);assert.deepEqual((await inbox.next(owner.id,0)).batch,morning)
  inbox.ack(owner.id,morning.id)
  inbox=create(args)
  for(const tick of [4,1000,6000,12000,23999]) {
    inbox.capture(daylight(11,tick));assert.equal(await next(),null)
  }
  // Natural dawn is a normal input even during a running action. No action is
  // replaced, and subsequent ticks, reconnects and clock rollback stay quiet.
  jobs([{id:'morning-walk',kind:'goto',status:'running'}])
  inbox.capture(daylight(12,0));const naturalMorning=await acknowledge()
  assert.deepEqual(naturalMorning.events.map(e=>[e.kind,e.day]),[['morning',12]])
  assert.equal(naturalMorning.priority,'normal')
  assert.equal(project(naturalMorning).world.action.name,'mc.goto')
  inbox.disconnected('morning-reconnect');inbox.capture(daylight(12,50))
  const morningReconnect=await acknowledge()
  assert.deepEqual(morningReconnect.events.map(e=>e.kind),['ready_again'])
  for(const [day,tick] of [[11,23999],[12,0],[12,1000]]) {
    inbox.capture(daylight(day,tick));assert.equal(await next(),null)
  }
  jobs([{id:'morning-walk',kind:'goto',status:'cancelled',reason:'action_replaced'}])
  // Skipped days produce one current morning, never a backlog. Nighttime
  // recovery drops obsolete mornings, and a different server starts fresh.
  inbox.capture(daylight(15,100));const skipped=await acknowledge()
  assert.deepEqual(skipped.events.map(e=>[e.kind,e.day]),[['morning',15]])
  inbox.capture(daylight(16,18000));assert.equal(await next(),null)
  inbox.capture(daylight(17,0,{ready:false}));assert.equal(await next(),null)
  inbox.capture(daylight(17,18000));assert.equal(await next(),null)
  inbox.capture(daylight(18,10,{dimension:'minecraft:the_nether'}));assert.equal(await next(),null)
  inbox.capture(daylight(18,20));const returned=await acknowledge()
  assert.deepEqual(returned.events.map(e=>[e.kind,e.day]),[['morning',18]])
  inbox.capture(daylight(1,100,{server:'other:25565'}));assert.equal(await next(),null)
  inbox.capture(death);inbox.capture(recovered);assert.equal(await next(),null)
  inbox.capture({...death,clientTick:320,observedAt:new Date(clock).toISOString()})
  const moreDamage=await next();if(moreDamage)inbox.ack(owner.id,moreDamage.id)
  inbox.capture({...recovered,clientTick:330});const secondRespawn=await acknowledge()
  assert.equal(secondRespawn.events.filter(e=>e.kind==='respawned').length,1)
  assert.notEqual(secondRespawn.events.find(e=>e.kind==='respawned').id,respawn.events[0].id)
  // A single player request receives the newest state, including nearby blocks.
  inbox.capture(frame(240,[chat(1)]));const first=await next();assert(first)
  const offeredContext=offer(first).context
  assert.equal(first.events[0].kind,'chat');assert.equal(first.world.nearbyBlocks.blocks[0].name,'minecraft:yellow_bed')
  assert.throws(()=>inbox.ack(owner.id,'unknown'))
  for(let i=241;i<=280;i++){clock+=250;inbox.capture(frame(i,[chat(1),chat(2,'new request')]))}
  assert.equal((await inbox.next(owner.id,0)).batch.id,first.id)
  inbox=create(args);assert.equal((await inbox.next(owner.id,0)).batch.id,first.id)
  // Reconstructing an offered brief after time passes or restart must preserve
  // its exact payload and action fence under the same durable delivery key.
  assert.deepEqual(offer((await inbox.next(owner.id,0)).batch).context,offeredContext)
  inbox.capture(frame(280,[chat(1),chat(2,'new request')]))
  const fresh=project(inbox.current(owner.id))
  assert.equal(context(fresh.world).state.player.pos[0],280)
  assert.equal(offeredContext.state.player.pos[0],240)
  assert.deepEqual(offeredContext.identity,project(first).world.identity)
  assert.equal(context(fresh.world,[],new Date(clock+6000).toISOString()).state.stale,true)
  assert.equal((await inbox.next(owner.id,0)).batch.id,first.id)
  inbox.ack(owner.id,first.id);inbox.capture(frame(280,[chat(1),chat(2,'new request')]))
  const second=await acknowledge();assert.equal(second.events.length,1)
  assert.equal(second.events[0].message.id,'chat:2');assert.equal(second.world.player.pos[0],280)
  assert.deepEqual(inbox.ack(owner.id,second.id),{acknowledged:second.id})
  assert.equal(await next(),null)
  // Own chat, advancements and other server text are consumed without wakeups,
  // including after a controller restart. Human chat still arrives once.
  const noise=[chat(1),chat(2),{...chat(3),kind:'server',text:'joined the game'},
    {...chat(4),kind:'server',translationKey:'chat.type.advancement.task'}, {...chat(5),self:true}]
  inbox.capture(frame(280,noise));assert.equal(await next(),null)
  inbox=create(args);inbox.capture(frame(280,noise));assert.equal(await next(),null)
  const messages=[...noise,chat(6,'continue')]
  inbox.capture(frame(281,messages));const request=await acknowledge()
  assert.equal(request.events.length,1);assert.equal(request.world.playerChatId,'chat:6')
  // Running progress and its world effects stay internal. Damage interrupts
  // that work, carries attacker identity, and is replayable until acknowledged.
  jobs([{id:'build',kind:'build',status:'running',progress:{completed:0}}])
  for(let i=0;i<240;i++) {
    clock+=250;inbox.capture({...frame(i,messages),inventory:[{name:'minecraft:stone',count:240-i}]})
    jobs([{id:'build',kind:'build',status:'running',progress:{completed:i}}])
    assert.equal((await inbox.next(owner.id,0)).batch,null)
  }
  const attacker={id:4,uuid:'zombie',type:'minecraft:zombie'}
  inbox.capture({...frame(241,messages),health:17,attacker})
  assert.equal((await inbox.next(owner.id,0)).batch,null)
  inbox.capture({...frame(242,messages),health:17,attacker})
  const hurt=await next();assert.equal(hurt.events.length,1);assert.equal(hurt.events[0].kind,'damaged')
  assert.equal(hurt.events[0].attacker.uuid,'zombie');assert.equal(hurt.world.player.pos[0],242)
  assert.equal(hurt.priority,'urgent')
  assert.equal(offer(hurt).context.state.threatResponse.awaitingResponse,true)
  inbox=create(args);assert.equal((await inbox.next(owner.id,0)).batch.id,hurt.id)
  inbox.ack(owner.id,hurt.id);inbox.capture({...frame(242,messages),health:17})
  assert.equal(await next(),null)
  jobs([{id:'build',kind:'build',status:'completed',progress:{completed:240},result:{placed:240}}])
  const done=await acknowledge();assert.equal(done.events[0].kind,'job_finished');assert.equal(done.events[0].job.result.placed,240)
  const completed=offer(done)
  assert.equal(completed.events[0].action.result.placed,240)
  assert.equal(completed.context.state.player.health,17)
  assert.equal(completed.context.state.lastAction.result.placed,240,'state retains the last result')
  // Fast completion and a blocker can occur entirely between sampler ticks.
  const fast={id:'instant',kind:'goto',status:'completed'}
  const blocked={id:'paused',kind:'build',status:'paused',reason:'missing_materials'}
  jobs([fast,blocked]);const terminal=await next()
  assert.deepEqual(terminal.events.map(e=>[e.kind,e.job.id]),[['job_finished','instant'],['job_blocked','paused']])
  inbox=create(args);assert.equal((await inbox.next(owner.id,0)).batch.id,terminal.id)
  inbox.ack(owner.id,terminal.id);inbox.capture({...frame(242,messages),health:17});jobs([fast,blocked])
  assert.equal(await next(),null)
  jobs([{...blocked,status:'cancelled',reason:'action_replaced'},{id:'follow',kind:'follow',status:'running'}])
  assert.equal(await next(),null)
  // Controller restart interrupts a running job exactly once; disconnect alone
  // is quiet, but the action result informs the retained player conversation.
  inbox=create(args);jobs([],true);inbox.capture({...frame(242,messages),health:17})
  const interrupted=await acknowledge();assert.equal(interrupted.events[0].kind,'job_interrupted')
  jobs([],true);assert.equal(await next(),null)
  // Partial inventory gain is a terminal handoff, retained with its actionable
  // blocker across an unacknowledged delivery and a controller restart.
  const gather={id:'gather',kind:'gather',status:'running',progress:{completed:1,remaining:1,
    held:1,phase:'mining',target:[0,72,0],approaches:1}}
  jobs([gather]);assert.equal(await next(),null)
  assert.deepEqual(offer(inbox.current(owner.id)).context.state.action.progress.target,[0,72,0])
  const partial={...gather,status:'partial',reason:'requires_elevation',result:{status:'partial',
    gained:1,remaining:1,complete:false,explanation:'Walking access failed above mining reach.',
    blocker:{target:[0,72,0],aboveReach:true,reach:4.5,approaches:1}}}
  jobs([partial]);const handoff=await next()
  assert.equal(handoff.events[0].kind,'job_finished')
  assert.deepEqual(offer(handoff).context.state.lastAction.result,partial.result)
  inbox=create(args);assert.deepEqual(offer((await inbox.next(owner.id,0)).batch).context,offer(handoff).context)
  inbox.ack(owner.id,handoff.id);inbox.capture({...frame(242,messages),health:17});jobs([partial])
  assert.equal(await next(),null)
  inbox.capture({...frame(242,messages),dead:true,health:0,ready:false})
  // A new owner inherits neither events nor effects from the old play session.
  owner={id:'play-two',generation:2,intent:'playing'}
  assert.throws(()=>inbox.current('play-one'))
  assert.throws(()=>inbox.ack('play-one',interrupted.id))
  assert.throws(()=>inbox.ack(owner.id,interrupted.id))
  inbox.capture({...frame(99,[chat(1)]),connection:{playSession:'play-one'}})
  assert.equal(await next(),null)
  inbox.capture(frame());const newOwnerReady=await acknowledge();assert.equal(newOwnerReady.events[0].kind,'ready')
  assert(!newOwnerReady.events.some(e=>e.kind==='respawned'))
  jobs([{playSession:'play-one',id:'follow',kind:'follow',status:'completed'}]);assert.equal(await next(),null)
  const waiting=inbox.next(owner.id,30000);inbox.stop();await assert.rejects(waiting,/controller_restarting/)
  inbox=create(args);owner.intent='ended';owner.endReason='player_requested_stop'
  assert.equal((await inbox.next(owner.id,0)).ended,true)
  console.log('Passed: event-only admission, newest block state, durable replay, chat filtering, terminal actions, restart and generation fencing')
}
run().finally(()=>fs.rmSync(dataDir,{recursive:true,force:true})).catch(e=>{console.error(e);process.exitCode=1})
