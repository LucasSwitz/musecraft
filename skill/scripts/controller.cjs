// One controller owns the visible Java player and its native actions.
if (Number(process.versions.node.split('.')[0]) !== 25) throw new Error('Musecraft requires Node 25')
const http = require('node:http')
const net = require('node:net')
const fs = require('node:fs')
const path = require('node:path')
const {AsyncLocalStorage} = require('node:async_hooks')
const execution = new AsyncLocalStorage()
const dataDir = process.env.MUSECRAFT_HOME || '/home/hatch/workspace/musecraft'
fs.mkdirSync(dataDir,{recursive:true,mode:0o700})
const socketPath=path.join(dataDir,'controller.sock')
const adapter=require('./adapter.cjs')({cfg:{dataDir},getContext:()=>execution.getStore()})
const play=require('./play-session.cjs')({dataDir,rpc:adapter.controlRpc,
  onDeath:doc=>inbox.capture(doc),cancel:()=>adapter.deadman().catch(()=>{})})
const inbox=require('./event-inbox.cjs')({dataDir,session:play.snapshot})
const invoke=require('./invoke.cjs')({dataDir,session:play.snapshot,endSession:endSession,adapter,execution,inbox})
const {project}=require('./turn-context.cjs')
const cli=require('./cli-session.cjs')({dataDir,play,inbox,invoke})
let observationStream
async function endSession(id,reason) {
  play.end(id,reason)
  const until=Date.now()+8500
  do {
    await play.tick()
    const state=play.snapshot()
    if(!state.cleanupPending)return state
    await new Promise(resolve=>setTimeout(resolve,100))
  } while(Date.now()<until)
  throw new Error('session ended; disconnect cleanup is still pending')
}
async function sessionRequest(operation) {
  if(operation==='status')return play.snapshot()
  if(operation==='retry') {
    const id=play.snapshot()?.id
    if(!id)throw new Error('not_playing: run `musecraft play` first')
    return play.retry(id)
  }
  throw new Error('Unknown session operation')
}
async function request(body) {
  if(body.action==='cli')return {ok:true,value:await cli(body)}
  if(body.action==='observe')return {ok:true,value:project(inbox.current(play.snapshot()?.id))}
  if(body.action==='health')return {ok:true,value:{pid:process.pid}}
  // Operator avatar setup (Figura): status, load PATH, upload, reload.
  if(body.action==='avatar')return {ok:true,value:await adapter.controlRpc('avatar',{operation:body.operation||'status',...(body.path?{path:body.path}:{})})}
  if(body.action==='session') {
    const value=await sessionRequest(body.operation)
    inbox.wake();return {ok:true,value}
  }
  throw new Error('Use cli, observe, health, session or avatar')
}
const server=http.createServer((req,res)=> {
  let body=''
  req.on('data',chunk=>{body+=chunk;if(Buffer.byteLength(body)>1100*1024)req.destroy()})
  req.on('end',async()=> {
    res.setHeader('Content-Type','application/json')
    try {
      if(req.method!=='POST'||req.url!=='/')throw new Error('Use POST /')
      const parsed=JSON.parse(body),gone=new AbortController()
      res.on('close',()=>{if(!res.writableFinished)gone.abort()})
      parsed.signal=gone.signal
      res.end(JSON.stringify(await request(parsed)))
    } catch(e) {res.statusCode=400;res.end(JSON.stringify({ok:false,error:{message:e.message}}))}
  })
})
server.on('error',e=>{console.error(e.message);process.exitCode=1})
async function start() {
  if(fs.existsSync(socketPath))await new Promise((resolve,reject)=> {
    const probe=net.createConnection(socketPath)
    probe.on('connect',()=>{probe.destroy();reject(new Error('A controller already owns this socket'))})
    probe.on('error',e=>{if(e.code==='ECONNREFUSED'){fs.unlinkSync(socketPath);resolve()}else if(e.code==='ENOENT')resolve();else reject(e)})
  })
  await adapter.deadman()
  inbox.updateJobs([],true)
  server.listen(socketPath,()=> {
    fs.chmodSync(socketPath,0o600)
    observationStream=require('./observation-stream.cjs')({dataDir,inbox})
    setInterval(()=>inbox.updateJobs(adapter.mc.jobs.list()),200).unref()
    setInterval(()=>play.tick(),1000).unref();play.tick()
  })
}
start().catch(e=>{console.error(e.message);process.exitCode=1})
process.on('SIGTERM',async()=>{observationStream?.stop();inbox.stop();await adapter.deadman();server.close();process.exit(0)})
