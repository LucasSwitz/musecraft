// Wake rules through the real inbox and CLI layer: idle costs nothing, standby
// wakes promptly with the events that need the player, an open command keeps
// its own events, and everything else waits for the next output.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path')
const createInbox=require('../skill/scripts/event-inbox.cjs'),createCli=require('../skill/scripts/cli-session.cjs')
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'musecraft-cli-'))
const session={id:'play',generation:1,intent:'playing',status:'playing',server:'host:25565',player:'muse_bot',owner:'owner_name'}
const inbox=createInbox({dataDir:dir,session:()=>session})
const jobs=rows=>inbox.updateJobs(rows.map(r=>({playSession:session.id,...r})))
const invoke=async body=>{
  if(['mc.follow','mc.gather'].includes(body.method))return {action:body.method,status:'running'}
  // A short physical action whose outcome event lands just after its result.
  if(body.method==='mc.closeScreen'){setTimeout(()=>jobs([{id:'close',kind:'closeScreen',status:'completed'}]),100);return {action:body.method,status:'completed'}}
  return {action:body.method,status:'completed'}
}
const cli=createCli({dataDir:dir,play:{snapshot:()=>({...session})},inbox,invoke})
const chat=[]
let tick=0
const frame=(extra={})=>({session:'client',observedAt:new Date().toISOString(),clientTick:++tick,
  connection:{playSession:session.id},connected:true,ready:true,player:'muse_bot',server:'host:25565',
  dimension:'minecraft:overworld',pos:[0,64,0],health:20,food:20,inventory:[],
  entities:[{id:7,uuid:'owner-uuid',type:'minecraft:player',name:'owner_name',pos:[3,64,0],distance:3,alive:true,lineOfSight:true}],
  chat:{messages:chat,nextCursor:{session:'client',seq:chat.length},oldestSeq:1},...extra})
const say=(from,text)=>{chat.push({id:'c'+(chat.length+1),seq:chat.length+1,kind:'player',self:false,senderName:from,text});inbox.capture(frame())}
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
const signals=[]
function standby() {
  const signal={aborted:false};signals.push(signal)
  let result=null
  const done=cli({verb:'standby',signal}).then(r=>{result={...r,at:Date.now()};return result})
  return {done,signal,result:()=>result}
}
async function main() {
  // Joining prints the full state once.
  inbox.capture(frame())
  const joined=await cli({verb:'state'})
  assert.match(joined.text,/"kind":"ready"/);assert.match(joined.text,/MINECRAFT CURRENT STATE/)
  // Idle and other players' chat never wake the player.
  let wake=standby()
  for(let i=0;i<4;i++){await sleep(500);inbox.capture(frame())}
  say('visitor','hello everyone')
  await sleep(2000)
  assert.equal(wake.result(),null,'idle play and unrelated chat must not wake the player')
  // The owner's chat wakes it within a second, with every pending event and a
  // one-line status instead of the full state.
  const sent=Date.now();say('owner_name','come here please')
  const woke=await wake.done
  assert.ok(woke.at-sent<1500,`standby took ${woke.at-sent} ms`)
  assert.match(woke.text,/^Minecraft needs you: chat from owner_name\./)
  assert.match(woke.text,/\{"kind":"chat","from":"visitor","text":"hello everyone"/)
  assert.match(woke.text,/\{"kind":"chat","from":"owner_name","text":"come here please"/)
  assert.match(woke.text,/owner: owner_name, entity 7, 3 blocks away/)
  assert.doesNotMatch(woke.text,/MINECRAFT CURRENT STATE|"sequence"|Standby is off/)
  // A continuous action returns once started instead of holding its command.
  wake=standby()
  let started=Date.now()
  const followed=await cli({verb:'act',method:'mc.follow',input:{entityId:7}})
  assert.ok(Date.now()-started<1500,'follow must not hold its command open');assert.match(followed.text,/"status":"running"/)
  // An open long action returns the owner's chat itself; standby stays asleep.
  const gather=cli({verb:'act',method:'mc.gather',input:{item:'minecraft:oak_log',count:4}})
  await sleep(300);say('owner_name','stop here')
  const gathered=await gather
  assert.match(gathered.text,/"status":"running"/);assert.match(gathered.text,/"text":"stop here"/)
  // The rest of the same shell chain cannot act before the player reads that
  // chat; chat still goes out, and a later decision acts normally.
  assert.match((await cli({verb:'act',method:'mc.goto',input:{target:[5,64,5]}})).text,/"status":"skipped".*\n.*Skipped: the command before this one/)
  assert.match((await cli({verb:'wait',input:{seconds:30}})).text,/Skipped: the command before this one/)
  assert.match((await cli({verb:'act',method:'mc.chat',input:{text:'ok'}})).text,/"status":"completed"/)
  await sleep(1100)
  assert.match((await cli({verb:'act',method:'mc.goto',input:{target:[5,64,5]}})).text,/"status":"completed"/)
  await sleep(2500)
  assert.equal(wake.result(),null,'events already printed by a command must not wake the player again')
  // A short action reports its own outcome, so it does not wake the player later.
  const closed=await cli({verb:'act',method:'mc.closeScreen',input:{}})
  assert.match(closed.text,/"kind":"action_finished"/)
  await sleep(2500)
  assert.equal(wake.result(),null)
  // Danger wakes.
  inbox.capture(frame({health:15}))
  assert.match((await wake.done).text,/^Minecraft needs you: you were hit\./)
  // A background action finishing wakes, after the last command has settled.
  await sleep(1600)
  jobs([{id:'walk',kind:'follow',status:'running'}])
  wake=standby()
  await sleep(300);jobs([{id:'walk',kind:'follow',status:'completed'}])
  const finished=await wake.done
  assert.match(finished.text,/^Minecraft needs you: your action finished\./);assert.match(finished.text,/"kind":"action_finished"/)
  // Chat naming the player wakes it even from someone else.
  await sleep(1600);wake=standby()
  say('visitor','muse_bot can you help?')
  assert.match((await wake.done).text,/^Minecraft needs you: chat from visitor naming you\./)
  // The owner joining or leaving wakes the player at once.
  const presence={session:'net',sequence:0,oldestSequence:1,events:[],players:[{uuid:'bot',name:'muse_bot'}],count:1,omitted:0}
  inbox.capture(frame({playerPresence:presence}))
  const move=(sequence)=>({...presence,sequence,events:[{sequence,kind:sequence%2?'player_joined':'player_left',player:{uuid:'owner-uuid',name:'owner_name'},observedAt:new Date().toISOString()}]})
  for(const [sequence,reason] of [[1,'joined'],[2,'left']]) {
    await sleep(1600);wake=standby()
    const at=Date.now();inbox.capture(frame({playerPresence:move(sequence)}))
    const out=await wake.done
    assert.match(out.text,new RegExp('^Minecraft needs you: owner_name '+reason+'\\.'));assert.ok(out.at-at<1500)
  }
  // A managed client that is down wakes the player to restart it.
  await sleep(1600)
  fs.writeFileSync(path.join(dir,'cli-config.json'),JSON.stringify({client:{display:':95',headless:true}}))
  wake=standby()
  assert.match((await wake.done).text,/^Minecraft needs you: the game client is not running\./)
  fs.rmSync(path.join(dir,'cli-config.json'))
  // A cancelled standby ends quietly.
  await sleep(1600);wake=standby();await sleep(300);wake.signal.aborted=true
  assert.equal((await wake.done).text,'Standby cancelled.')
  console.log('Passed idle without wakes, owner chat wake with events and status, command-owned events, short-action outcome, danger, background outcome, name mention, owner join and leave, client down and cancellation')
}
main().finally(()=>{for(const signal of signals)signal.aborted=true;inbox.stop();fs.rmSync(dir,{recursive:true,force:true})}).catch(e=>{console.error(e);process.exitCode=1})
