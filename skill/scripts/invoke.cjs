// Durable tool receipts and a single replaceable native action; no evaluated code.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const {tools,validate} = require('./tool-api.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const active = job => ['running','paused','cancelling'].includes(job.status)
const canonical = value => Array.isArray(value)?value.map(canonical)
  :value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value
module.exports = ({dataDir,session,endSession,adapter,execution,inbox}) => {
  const directory=path.join(dataDir,'invocations')
  fs.mkdirSync(directory,{recursive:true,mode:0o700})
  const orderFile=path.join(directory,'action-order.json')
  let order=null,chain=Promise.resolve()
  try {order=JSON.parse(fs.readFileSync(orderFile,'utf8'))} catch(e) {if(e.code!=='ENOENT')throw e}
  function save(file,value) {
    const fd=fs.openSync(file+'.tmp','w',0o600)
    try {fs.writeFileSync(fd,JSON.stringify(value));fs.fsyncSync(fd)} finally {fs.closeSync(fd)}
    fs.renameSync(file+'.tmp',file)
    const dir=fs.openSync(directory,'r');try {fs.fsyncSync(dir)} finally {fs.closeSync(dir)}
  }
  function finish(file,receipt,body) {
    receipt.completedAt=Date.now();save(file,receipt)
    fs.appendFileSync(path.join(dataDir,'ledger.jsonl'),JSON.stringify({at:new Date().toISOString(),method:body.method,input:body.input,sequence:body.sequence,result:receipt.result,ms:receipt.completedAt-receipt.startedAt})+'\n')
    return copy(receipt.result)
  }
  async function execute(body) {
    const tool=Object.hasOwn(tools,body.method)?tools[body.method]:null
    if(!tool)throw new Error('Unknown high-level method')
    validate(tool.parameters,body.input)
    if(typeof body.callId!=='string'||!body.callId.length||body.callId.length>256)throw new Error('Invalid invocation identity')
    if(!Number.isSafeInteger(body.sequence)||body.sequence<1)throw new Error('Invalid invocation sequence')
    const current=session()
    if(!current||current.id!==body.playSession||current.generation!==body.generation)throw new Error('play_session_changed')
    const receiptFile=path.join(directory,crypto.createHash('sha256').update(`${current.id}:${current.generation}:${body.callId}`).digest('hex')+'.json')
    const hash=crypto.createHash('sha256').update(JSON.stringify([body.method,body.input,body.sequence,body.world])).digest('hex')
    let receipt
    try {receipt=JSON.parse(fs.readFileSync(receiptFile,'utf8'))} catch(e) {if(e.code!=='ENOENT')throw e}
    if(receipt) {
      if(receipt.hash!==hash)throw new Error('invocation_input_changed')
      if(!receipt.result) {receipt.result={action:body.method,status:'uncertain',reason:'controller_restarted_during_invocation; inspect current state before another action'};save(receiptFile,receipt)}
      return receipt.result
    }
    if(body.method!=='mc.endSession' && current.intent!=='playing')throw new Error('play_session_ended')
    let observed
    if(tool.action) {
      observed=await adapter.observe()
      if(!body.world || body.world.session!==observed.session || body.world.server!==(observed.server??null) || body.world.dimension!==(observed.dimension??null))throw new Error('observed_world_changed; wait for fresh state')
      if(body.world.life!==undefined && body.world.life!==inbox?.current(current.id)?.world.life)throw new Error('observed_life_changed; player died after this decision; wait for fresh state')
      if(body.world.clientTick!==undefined && (!Number.isSafeInteger(body.world.clientTick)||body.world.clientTick<0))throw new Error('invalid_observed_client_tick')
    }
    if(tool.action && order?.generation===current.generation && body.sequence<=order.sequence)throw new Error('stale_action_sequence')
    receipt={hash,method:body.method,sequence:body.sequence,startedAt:Date.now()}
    save(receiptFile,receipt) // Effects start only after durable admission.
    const actionKey=crypto.createHash('sha256').update(JSON.stringify([body.method,canonical(body.input)])).digest('hex')
    // A queued frame can predate the action this conversation just started.
    // Repeating active work must not cancel it. A new call after completion is
    // a new intent even when the admission frame predates the finished action.
    const previousJob=adapter.mc.jobs.list().find(j=>j.id===order?.jobId)
    const duplicate=tool.action && (!previousJob||active(previousJob)) && observed && order?.generation===current.generation
      && order.playSession===current.id && order.actionKey===actionKey
      && order.playerChatId===(body.world.playerChatId??null)
      && order.worldSession===observed.session && Number.isSafeInteger(body.world.clientTick)
      && Number.isSafeInteger(order.clientTick) && body.world.clientTick<order.clientTick
    if(duplicate) {
      const previous=JSON.parse(fs.readFileSync(path.join(directory,order.receiptName),'utf8'))
      const job=previousJob
      receipt.jobId=job?.id
      const result=job?{action:body.method,status:job.status,reason:job.reason,progress:job.progress,result:job.result}
        :previous.result && !['running','uncertain'].includes(previous.result.status)?previous.result
        :{action:body.method,status:'uncertain',reason:'previous_action_outcome_unavailable; inspect current state'}
      order.sequence=body.sequence;save(orderFile,order)
      receipt.result={...result,coalesced:true}
      return finish(receiptFile,receipt,body)
    }
    const context={name:body.method,id:body.callId,playSession:current.id}
    try {
      const defense=adapter.mc.jobs.list().find(j=>active(j)&&['attack','flee'].includes(j.kind))
      const threat=inbox?.current(current.id)?.world.threatResponse
      const defending=defense && (defense.kind==='attack' || !threat || threat.trigger || threat?.awaitingResponse)
      if(tool.action && defending && !['mc.attack','mc.flee','mc.queueAttack'].includes(body.method)
          && !(body.method==='mc.respawn' && observed?.dead===true)) {
        receipt.result={ok:false,action:body.method,status:'blocked',reason:'defense_in_progress',
          defense:{action:'mc.'+defense.kind,status:defense.status},
          message:'The current attack or escape has priority. Switch with `musecraft attack` or `musecraft flee`, or run `musecraft wait` to await it. Ordinary work can continue after combat ends or the escape is safe.'}
        return finish(receiptFile,receipt,body)
      }
      // Validate an escape update before releasing any running action. The mod
      // fences entity identity again when admitting or extending the movement.
      const input=body.method==='mc.flee'?await adapter.prepareFlee(body.input):body.input
      const extendingFlee=body.method==='mc.flee' && adapter.mc.jobs.list().some(j=>j.kind==='flee'&&j.status==='running')
      if(tool.action) {
        order={playSession:current.id,generation:current.generation,sequence:body.sequence,
          actionKey,worldSession:observed?.session,clientTick:observed?.clientTick,
          playerChatId:body.world?.playerChatId??null,
          receiptName:path.basename(receiptFile)};save(orderFile,order)
        if(tool.action!=='append'&&!extendingFlee)await Promise.all(adapter.mc.jobs.list().filter(active).map(j=>adapter.mc.jobs.cancel(j.id,'action_replaced')))
      }
      const raw=await execution.run(context,async()=> {
        if(body.method==='mc.endSession')return endSession(current.id,body.input.reason)
        if(body.method==='screen.capture')return adapter.screen.capture()
        return adapter.mc[body.method.slice(3)](...tool.args(input))
      })
      receipt.result=tool.action && raw?.id ? {action:body.method,status:raw.status,reason:raw.reason,progress:raw.progress}
        : tool.action ? {action:body.method,status:raw?.status||'completed',reason:raw?.reason,result:raw?.observed??raw} : raw
      if(tool.action && raw?.id) {receipt.jobId=raw.id;order.jobId=raw.id;save(orderFile,order)}
    } catch(e) {
      const missing=body.method==='mc.flee'&&/target_gone:(\d+)/.exec(e.message)
      receipt.result={ok:false,action:body.method,status:'blocked',reason:missing?'target_gone':e.message,
        ...(missing?{entityId:Number(missing[1]),explanation:`Entity ${missing[1]} is no longer available. Choose a currently observed threat. The current action was left unchanged.`}:{})}
    }
    finally {await adapter.releaseOwner(context)}
    return finish(receiptFile,receipt,body)
  }
  const invoke = body=>{
    // An attack or escape is protected from urgent interruption from the
    // moment it is requested, including while it waits behind an earlier call.
    const defense=['mc.attack','mc.flee','mc.queueAttack'].includes(body.method)
    if(defense)inbox?.defenseCall(1)
    const result=chain.then(async()=> {
    let value
    try {value=await execute(body)} finally {if(defense)inbox?.defenseCall(-1)}
    const situation=inbox?.current(body.playSession)?.world.threatResponse
    // Results expose new danger even within the same agent turn. Keep the
    // action receipt unchanged; a retry must not replay the effect or rearm it.
    return body.world?.threatResponse && situation?.receipt.through
      ? {...(value && typeof value==='object' && !Array.isArray(value) ? value : {result:value}),threatUpdate:situation} : value
  });chain=result.catch(()=>{});return result}
  return invoke
}
