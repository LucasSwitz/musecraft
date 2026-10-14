// Durable cancellation, resume and reconnect races; no Minecraft or inference dependency.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const makeSession = require('../skill/scripts/play-session.cjs')
const dir = fs.mkdtempSync(path.join(os.tmpdir(),'musecraft-session-'))
let clock = Date.now(), calls = [], cancelled = 0, hook = null, submitted = true, observed = null
const rpc = async (method, params) => {
  calls.push({method,params})
  if (hook) await hook(method)
  if(method === 'observe') return observed ?? {connected:false,connection:{connecting:false}}
  if(method === 'connectServer') return {submitted}
  return {}
}
function manager() { return makeSession({dataDir:dir,rpc,now:()=>clock,cancel:()=>{
  const state=makeSession.read(dir); assert.ok(state.intent==='ended' || state.status==='blocked'); cancelled++
}}) }
async function main() {
  let session = manager()
  const options={id:'first',server:'example.org:25565',player:'muse_bot',owner:'owner_name'}
  session.start(options)
  let release, entered
  const gate = new Promise(r=>release=r), ready = new Promise(r=>entered=r)
  hook=async method=>{if(method==='observe'){entered();await gate}}
  const pending=session.tick();await ready
  session.end('first','explicit user stop');release();await pending
  assert.equal(calls.filter(x=>x.method==='connectServer').length,0,'end must fence an in-flight observation before reconnect')
  assert.equal(cancelled,1)
  session=manager();hook=null;await session.tick()
  assert.equal(session.snapshot().intent,'ended');assert.equal(session.snapshot().cleanupPending,false)
  assert.equal(session.start(options).intent,'ended','idempotent start cannot revive terminal intent')
  assert.throws(()=>session.retry('first'),/ended/)
  assert.throws(()=>makeSession.assertPlaying(dir,'first'),/ended/)
  session.start({...options,id:'second'});assert.throws(()=>session.end('first','late stop'),/changed/)
  assert.throws(()=>makeSession.assertPlaying(dir,'first'),/changed/)
  assert.equal(session.snapshot().intent,'playing')
  session.end('second','finished')
  assert.throws(()=>session.start(options),/already_used/)
  session.start({...options,id:'third'})
  session=manager();assert.equal(session.snapshot().id,'third')
  submitted=false
  for(let i=0;i<7;i++){await session.tick();clock+=1000}
  assert.equal(session.snapshot().attempts,0,'client loading must not consume reconnect attempts')
  assert.equal(session.snapshot().nextAttemptAt,0,'loading deferral must not accumulate network backoff')
  submitted=true
  for(let i=0;i<7;i++){await session.tick();clock=Math.max(clock,session.snapshot().nextAttemptAt)+1}
  assert.equal(session.snapshot().status,'recovering')
  assert.equal(session.snapshot().attempts,7,'transient reconnect keeps retrying')
  await session.tick()
  const attempts=calls.filter(x=>x.method==='connectServer').length
  clock+=59999;await session.tick()
  assert.equal(calls.filter(x=>x.method==='connectServer').length,attempts,'reconnect backoff prevents a hot retry loop')
  clock+=1;await session.tick()
  assert.equal(calls.filter(x=>x.method==='connectServer').length,attempts+1)
  session.end('third','finished');await session.tick()
  assert.equal(calls.filter(x=>x.method==='connectServer').length,attempts+1,'explicit stop fences further retry')
  session.start({...options,id:'fourth'})
  const connectGate=new Promise(r=>release=r), connectReady=new Promise(r=>entered=r)
  hook=async method=>{if(method==='connectServer'){entered();await connectGate}}
  const connecting=session.tick();await connectReady
  session.end('fourth','stop during connection admission');release();await connecting
  assert.equal(session.snapshot().status,'stopped','a late admission result cannot revive stopped play')
  assert.equal(session.snapshot().attempts,0)
  hook=null;await session.tick()
  assert.equal(session.snapshot().cleanupPending,false)
  // `musecraft play` resumes the active session: a blocker clears for another
  // attempt and the owner can change, but a different server or player cannot.
  const definition={server:options.server,player:options.player}
  const resumed=session.resume({...definition,owner:'owner_name'})
  assert.equal(resumed.intent,'playing');assert.equal(resumed.owner,'owner_name')
  observed={connected:false,connection:{connecting:false,reason:'You are not white-listed on this server'}}
  await session.tick();assert.equal(session.snapshot().status,'blocked')
  const blockedConnects=calls.filter(c=>c.method==='connectServer').length
  await session.tick()
  assert.equal(calls.filter(c=>c.method==='connectServer').length,blockedConnects,'a blocked session does not reconnect')
  assert.equal(calls.filter(c=>c.method==='playPolicy').at(-1).params.active,false)
  observed=null
  session.resume({...definition,owner:'new_owner'})
  assert.equal(session.snapshot().id,resumed.id);assert.equal(session.snapshot().status,'recovering')
  assert.equal(session.snapshot().owner,'new_owner')
  assert.throws(()=>session.resume({...definition,server:'other.example.org'}),/definition_changed/)
  assert.throws(()=>session.resume({...definition,owner:'not a name'}),/owner must be/)
  session=manager();assert.equal(session.snapshot().owner,'new_owner')
  await session.tick();assert.equal(calls.filter(c=>c.method==='playPolicy').at(-1).params.active,true)
  session.end(resumed.id,'done');await session.tick()
  // Fast automatic respawn must persist death even if the stream misses it.
  const makeInbox=require('../skill/scripts/event-inbox.cjs')
  let inbox, deathRecorded=false
  const dying={connected:true,ready:false,dead:true,health:0,player:'muse_bot',server:options.server,
    session:'client:respawn',clientTick:1,connection:{playSession:'fast-respawn'},pos:[1,64,2],
    observedAt:new Date(clock).toISOString()}
  session=makeSession({dataDir:dir,now:()=>clock,cancel:()=>{},onDeath:doc=>inbox.capture(doc),
    rpc:async method=>{
      if(method==='observe')return dying
      if(method==='respawn') {
        deathRecorded=!!JSON.parse(fs.readFileSync(path.join(dir,'observation-inbox.json'))).pendingRespawn
        assert(deathRecorded)
      }
      return {}
    }})
  session.start({...options,id:'fast-respawn'})
  inbox=makeInbox({dataDir:dir,session:session.snapshot,now:()=>clock})
  await session.tick();assert(deathRecorded)
  inbox=makeInbox({dataDir:dir,session:session.snapshot,now:()=>clock})
  inbox.capture({...dying,dead:false,health:20,ready:true,clientTick:2,pos:[0,64,0]})
  clock+=201
  const respawn=(await inbox.next('fast-respawn',0)).batch
  assert.deepEqual(respawn.events.map(e=>e.kind),['died','respawned'])
  console.log('Passed cancellation race, durable stop, idempotent replay, resume with blocker and owner change, session restore, bounded reconnect and fast respawn scenarios')
}
main().finally(()=>fs.rmSync(dir,{recursive:true,force:true})).catch(e=>{console.error(e);process.exitCode=1})
