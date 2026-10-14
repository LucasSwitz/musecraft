// Independent control traffic and cancellation of long-running builds.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net')
const root=fs.mkdtempSync(path.join(os.tmpdir(),'musecraft-liveness-'))
fs.cpSync(path.join(__dirname,'../skill/scripts'),path.join(root,'scripts'),{recursive:true})
const release=require('../release.json');fs.writeFileSync(path.join(root,'release.json'),JSON.stringify(release))
let entered,releaseRead
const pendingRead=new Promise(r=>entered=r),gate=new Promise(r=>releaseRead=r)
const received=[]
let gameplay=null
const server=net.createServer(socket=>{
 let text=''
 socket.on('data',async chunk=>{
  text+=chunk;if(!text.includes('\n'))return
  const request=JSON.parse(text);received.push(request.method)
  let value=request.method
  if(gameplay)value=await gameplay(request)
  else if(request.method==='observe'){entered();await gate}
  socket.end(JSON.stringify({ok:true,value,compatibility:release})+'\n')
 })
})
async function main(){
 await new Promise(r=>server.listen(path.join(root,'client.sock'),r))
 const adapter=require(path.join(root,'scripts/adapter.cjs'))({cfg:{dataDir:root}})
 const first=adapter.rpc('observe');await pendingRead
 const second=adapter.rpc('inventory')
 assert.equal(await adapter.controlRpc('playPolicy',{active:false}),'playPolicy')
 assert.deepEqual(received,['observe','playPolicy'])
 releaseRead();await first;await second
 assert.deepEqual(received,['observe','playPolicy','inventory'])
 await buildLifetime()
 await buildLifetime(true)
 await continuousLifetime()
 console.log('Passed independent control lane, productive build, fixed 10-minute cap, cancellation fencing, partial recovery, timeout event delivery and continuous follow/flee')
}

async function buildLifetime(timeout=false){
 const {AsyncLocalStorage}=require('node:async_hooks'),execution=new AsyncLocalStorage()
 const adapter=require(path.join(root,'scripts/adapter.cjs'))({cfg:{dataDir:root},getContext:()=>execution.getStore()})
 const realNow=Date.now;let offset=0,revision=0,placements=0,stopCount=0
 Date.now=()=>realNow()+offset
 const session=require(path.join(root,'scripts/play-session.cjs'))({dataDir:root,rpc:async()=>{},cancel:()=>{}})
 session.start({id:'build',server:'test.invalid',player:'test'})
 const blocks=new Map(),plan=[0,1,2,3].map(x=>({pos:[x,64,0],block:'minecraft:stone'}))
 const air={name:'minecraft:air',properties:{},replaceable:true}
 const world={session:'world',connected:true,ready:true,canFly:false,pos:[0,64,0]}
 let enteredPlacement,releasePlacement
 const atPlacement=new Promise(r=>enteredPlacement=r),placementGate=new Promise(r=>releasePlacement=r)
 let native=null
 const state=()=>({...native,verified:blocks.size,remaining:plan.length-blocks.size,changed:{placed:blocks.size,cleared:0}})
 const place=()=>{const item=plan.find(b=>!blocks.has(String(b.pos)));if(item){blocks.set(String(item.pos),{name:item.block,properties:{}});offset+=65000;placements++}}
 gameplay=async({method,params:p})=>{
  if(method==='observe')return world
  if(method==='inventory')return [{slot:0,name:'minecraft:stone',count:64}]
  if(method==='buildStart'){
   native={id:'native-'+revision++,planId:p.planId,status:'running',phase:'building',backend:'baritone',total:4,blockers:[],cleanup:{remaining:0,targets:[]},missing:{}}
   place();if(blocks.size<3)place()
   if(blocks.size===4)native={...native,status:'completed',reason:'build_complete',complete:true}
   return state()
  }
  if(method==='buildPoll'){
   place();if(placements===3){enteredPlacement();await placementGate}
   if(blocks.size===4)native={...native,status:'completed',reason:'build_complete',complete:true}
   return state()
  }
  if(method==='buildStatus')return state()
  if(method==='stop'){stopCount++;if(native)native={...native,status:'cancelled',reason:'action_cancelled',complete:false};return {stopped:true}}
  return {}
 }
 try{
  const {mock}=require('node:test')
  if(timeout)mock.timers.enable({apis:['setTimeout']})
  // A shorter caller-supplied timeout cannot override the shared policy.
  const job=execution.run({name:'build',playSession:'build'},()=>adapter.mc.build(plan,{timeoutMs:120000}))
  const early=await Promise.race([atPlacement.then(()=>false),adapter.mc.jobs.wait(job.id,3000).then(()=>true)])
  assert.equal(early,false,'build must reach its third placement after 130 seconds of productive work')
  assert.equal(adapter.mc.jobs.status(job.id).status,'running')
  if(timeout){
   offset=600000
   mock.timers.tick(600000);mock.timers.reset()
   assert.equal(adapter.mc.jobs.status(job.id).status,'cancelling')
  }
  // A replacement racing the deadline cannot erase the timeout reason. The
  // action stays nonterminal until its in-flight RPC and native cleanup finish.
  const cancelling=adapter.mc.jobs.cancel(job.id,'action_replaced')
  releasePlacement()
  const cancelled=await cancelling
  assert.equal(cancelled.status,timeout?'blocked':'cancelled');assert.equal(cancelled.reason,timeout?'action_timeout':'action_replaced')
  assert.deepEqual(cancelled.result.blockers,[],'interruption must not invent failures for unattempted placements')
  assert.equal(placements,3,'cancellation must fence the next placement even after a late RPC reply')
  assert.ok(stopCount>0,'cancellation must clear native controls')
  if(timeout){
   assert.equal(cancelled.result.blocker.timeoutMs,600000)
   assert.ok(cancelled.result.blocker.elapsedMs>=600000)
   const createInbox=require(path.join(root,'scripts/event-inbox.cjs'))
   const args={dataDir:root,urgentMs:0,session:()=>({id:'build',generation:1,intent:'playing'})}
   let inbox=createInbox(args)
   const frame={...await adapter.observe(),connection:{playSession:'build'}}
   assert.equal(frame.jobs.find(j=>j.id===job.id).result.blocker.reason,'action_timeout')
   inbox.capture(frame)
   const ready=(await inbox.next('build',0)).batch
   inbox.ack('build',ready.id)
   inbox.updateJobs(frame.jobs)
   const batch=(await inbox.next('build',0)).batch
   assert.equal(batch.events.length,1);assert.equal(batch.events[0].kind,'job_blocked')
   assert.equal(batch.events[0].job.result.blocker.reason,'action_timeout')
   inbox.ack('build',batch.id);inbox=createInbox(args);inbox.capture(frame);inbox.updateJobs(frame.jobs)
   assert.equal((await inbox.next('build',0)).batch,null,'timeout delivers once, including after inbox recovery')
  }
  const next=execution.run({name:'replacement',playSession:'build'},()=>adapter.mc.build(plan))
  const completed=await adapter.mc.jobs.wait(next.id,3000)
  assert.equal(completed.status,'completed');assert.equal(completed.result.verified,4)
  assert.equal(placements,4,'replacement verifies existing blocks and only places the missing one')
 } finally {releasePlacement();Date.now=realNow;require('node:test').mock.timers.reset()}
}
async function continuousLifetime(){
 const {AsyncLocalStorage}=require('node:async_hooks'),execution=new AsyncLocalStorage(),{mock}=require('node:test')
 const adapter=require(path.join(root,'scripts/adapter.cjs'))({cfg:{dataDir:root},getContext:()=>execution.getStore()})
 const session=require(path.join(root,'scripts/play-session.cjs'))({dataDir:root,rpc:async()=>{},cancel:()=>{}})
 session.start({id:'build',server:'test.invalid',player:'test'})
 for(const kind of ['follow','flee']){
  const entered=Promise.withResolvers(),gate=Promise.withResolvers();let held=false
  gameplay=async({method})=>{
   if(method==='observe')return {session:'world',connected:true,ready:true,pos:[0,64,0],entities:[{id:1,uuid:'target',alive:true,pos:[0,64,0]}]}
   if(method==='moveStart')return {id:'flee'}
   if(!held&&method===(kind==='follow'?'stop':'movePoll')){held=true;entered.resolve();await gate.promise}
   return {status:'running',phaseRevision:1}
  }
  try{
   mock.timers.enable({apis:['setTimeout']})
   const job=await execution.run({name:kind,playSession:'build'},()=>kind==='follow'?adapter.mc.follow(1):adapter.mc.flee({entityId:1}))
   await entered.promise;mock.timers.tick(600001);mock.timers.reset()
   assert.equal(adapter.mc.jobs.status(job.id).status,'running',kind+' must remain continuous past the finite-action limit')
   const cancelled=adapter.mc.jobs.cancel(job.id,'action_replaced');gate.resolve()
   assert.equal((await cancelled).reason,'action_replaced')
  }finally{gate.resolve();mock.timers.reset()}
 }
}
main().finally(()=>{releaseRead();server.close();fs.rmSync(root,{recursive:true,force:true})}).catch(e=>{console.error(e);process.exitCode=1})
