// Latest state is replaceable. Important events and offered batches are durable.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const response = require('./threat-response.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const active = job => ['running', 'cancelling', 'paused'].includes(job.status)

function world(doc, session, jobs) {
  return {
    playSessionId: session.id, generation: session.generation, clientEpoch: doc.session,
    observedAt: doc.observedAt, clientTick:doc.clientTick, connected: !!doc.connected, ready: !!doc.ready, joining:!!doc.joining,
    screen: doc.screen, clientError: doc.clientError, server: doc.server, dimension: doc.dimension,
    playSession: {intent:session.intent,status:session.status,error:session.error}, owner: session.owner ?? null,
    connection: { reason: doc.connection?.reason, connecting: doc.connection?.connecting },
    player: { name: doc.player, pos: doc.pos, health: doc.health, dead: doc.dead,
      food: doc.food, air: doc.air, submerged: doc.submerged, mode: doc.gameMode,
      selectedItem: doc.selectedItem, selectedSlot: doc.selectedSlot, vehicle:doc.vehicle, sleeping:doc.sleeping },
    inventory: doc.inventory || [], target: doc.target, nearbyBlocks:doc.nearbyBlocks, timeOfDay:doc.timeOfDay,
    container: doc.container ? require('./inventory-operations.cjs').view(doc.container) : null,
    entities: doc.entities || [], entityScope: doc.entityScope, droppedItems:doc.droppedItems,
    visibleHostiles:doc.visibleHostiles,
    onlinePlayers:doc.playerPresence ? {players:doc.playerPresence.players,count:doc.playerPresence.count,omitted:doc.playerPresence.omitted} : null,
    jobs: jobs.filter(active).concat(jobs.filter(j => !active(j)).slice(-2)),
  }
}

module.exports = ({dataDir, session, now = Date.now, urgentMs = 200}) => {
  const file = path.join(dataDir, 'observation-inbox.json')
  let stored = {seq:0, ack:0, revision:0, deliveredRevision:0, events:[], offered:null, lastDelivery:0}
  try { stored = JSON.parse(fs.readFileSync(file, 'utf8')) } catch (e) { if (e.code !== 'ENOENT') throw e }
  let freshFrame = false
  let latest = stored.latest || null, jobs = stored.jobs || [], stopped = false
  let urgentAt = null, defenseCalls = 0, defenseSettling = false
  const waiters = new Set()
  function save() {
    const fd = fs.openSync(file + '.tmp', 'w', 0o600)
    try { fs.writeFileSync(fd, JSON.stringify({...stored, latest, jobs})); fs.fsyncSync(fd) }
    finally { fs.closeSync(fd) }
    fs.renameSync(file + '.tmp', file)
    const directory = fs.openSync(dataDir, 'r')
    try { fs.fsyncSync(directory) } finally { fs.closeSync(directory) }
  }
  function wake() { for (const resolve of waiters) resolve(); waiters.clear() }
  function bind() {
    const current = session()
    if (!current) return null
    if (stored.sessionId !== current.id || stored.generation !== current.generation) {
      stored = {sessionId:current.id, generation:current.generation, seq:0, ack:0, revision:0, deliveredRevision:0, events:[], offered:null, lastDelivery:0}
      latest = null; freshFrame = false; urgentAt = null; jobs = []; save(); wake()
    }
    return current
  }
  function own(id) {
    const current = bind()
    if (!current || current.id !== id) throw new Error('play_session_changed')
    return current
  }
  function append(kind, fields = {}) {
    const sequence = ++stored.seq
    stored.revision++
    const event = {id:`${stored.sessionId}:${stored.generation}:${sequence}`, sequence,
      kind, observedAt:new Date(now()).toISOString(), clientEpoch:latest?.clientEpoch, ...fields}
    if (response.capture(stored,event,latest,now())) stored.events.push(event)
    // Never silently imply that a bounded queue contains every event.
    if (stored.events.length > 512) {
      const removed = stored.events.splice(0, stored.events.length - 512)
      stored.gap = {reason:'event_retention_exceeded', from:stored.gap?.from ?? removed[0].sequence,
        through:removed.at(-1).sequence}
    }
    urgentAt ??= now() + urgentMs
  }
  function capture(doc) {
    const current = bind()
    if (!current || current.intent !== 'playing') { wake(); return }
    // Ignore frames from a preceding play session until the mod binds this one.
    if (doc.connection?.playSession !== current.id) return
    const previous = latest
    // Recovery's direct observation and the stream share the same tick order.
    if (previous?.clientEpoch === doc.session && Number.isSafeInteger(doc.clientTick)
        && doc.clientTick < previous.clientTick) return
    freshFrame = true
    latest = world(doc, current, jobs)
    let persist = false
    const event = (kind, fields) => {append(kind, fields); persist = true}
    // Death does not disconnect the client or change its epoch. Retain the
    // pending transition across controller restart, then wake once able to act.
    if (latest.connected && (doc.dead === true || doc.health === 0) && !stored.pendingRespawn) {
      stored.life=(stored.life??0)+1
      stored.pendingRespawn = {observedAt:doc.observedAt, pos:doc.pos, dimension:doc.dimension}
      event('died', {death:stored.pendingRespawn})
    }
    // A returning ready client must resume the retained conversation even when
    // no action remains to finish. Persist the edge so stream/controller replay
    // cannot redeliver it. Menus alone do not count as connection loss.
    if (previous && stored.readyEpoch && (!previous.connected || previous.joining || !latest.connected || latest.joining || previous.clientEpoch !== latest.clientEpoch)) {
      if (!stored.awaitingReady) {stored.awaitingReady=true; persist=true}
    }
    if (stored.pendingRespawn && latest.connected && latest.ready && !latest.joining
        && doc.dead === false && doc.health > 0) {
      event('respawned', {death:stored.pendingRespawn})
      delete stored.pendingRespawn
      stored.awaitingReady=false
      stored.readyEpoch=latest.clientEpoch
    } else if (stored.awaitingReady && latest.connected && latest.ready && !latest.joining) {
      event('ready_again', {previousClientEpoch:stored.readyEpoch??previous?.clientEpoch??null})
      stored.awaitingReady=false
      stored.readyEpoch=latest.clientEpoch
    }
    if (!stored.readyEpoch && latest.connected && latest.ready && !latest.joining) {
      event('ready', {previousClientEpoch:null})
      stored.readyEpoch=latest.clientEpoch
    }
    persist=require('./morning-events.cjs')(stored,doc,event)||persist
    if (doc.damage) {
      persist=require('./damage-events.cjs')(stored,doc,previous?.player.health,now(),event)||persist
    } else if (previous?.connected && latest.connected && previous.clientEpoch === latest.clientEpoch
        && previous.player.health > latest.player.health)
      event('damaged', {amount:previous.player.health-latest.player.health, attacker:doc.attacker??null,
        critical:previous.player.health>6 && latest.player.health<=6})
    persist=require('./threat-events.cjs')(stored,doc,now(),event)||persist
    persist=response.refresh(stored,latest,now())||persist
    const presence = doc.playerPresence
    if (presence && (stored.presenceCursor?.session !== presence.session || stored.presenceCursor?.sequence !== presence.sequence)) {
      // A fresh play owner adopts the current roster. Controller recovery keeps
      // its cursor; a new network connection begins its own transition sequence.
      const after = !stored.presenceCursor ? presence.sequence
        : stored.presenceCursor.session === presence.session ? stored.presenceCursor.sequence : 0
      if (presence.oldestSequence > after + 1) stored.gap = {reason:'player_presence_retention_exceeded',through:stored.seq}
      for (const transition of presence.events || []) if (transition.sequence > after)
        event(transition.kind, {player:transition.player,observedAt:transition.observedAt})
      stored.presenceCursor = {session:presence.session,sequence:presence.sequence}; persist = true
    }
    const cursor = doc.chat?.nextCursor
    if (cursor && (stored.chatCursor?.session !== cursor.session || stored.chatCursor?.seq !== cursor.seq)) {
      const after = stored.chatCursor?.session === cursor.session ? stored.chatCursor.seq : 0
      if (doc.chat.oldestSeq > after + 1) stored.gap = {reason:'client_chat_retention_exceeded',through:stored.seq}
      for (const message of doc.chat.messages || []) if (message.seq > after && !message.self && message.kind==='player') {
        stored.playerChatId=message.id
        event('chat', {message})
      }
      stored.chatCursor = cursor; persist = true
    }
    latest.playerChatId=stored.playerChatId??null
    if (persist) {save(); wake()}
  }
  function updateJobs(records, interrupted = false) {
    const current = bind()
    if (!current || current.intent !== 'playing') return
    const compact = records.filter(j => j.playSession === current.id).map(j => ({id:j.id, kind:j.kind, status:j.status, reason:j.reason,
      targets:j.progress?.targets,queue:j.progress?.queue,outcomes:j.progress?.outcomes,entityUuid:j.progress?.uuid, entityId:j.progress?.entityId, escape:j.progress?.escape, done:j.progress?.completed, left:j.progress?.remaining, mined:j.progress?.mined, cleared:j.progress?.cleared,removed:j.progress?.removed,alreadyClear:j.progress?.alreadyClear,digTarget:j.progress?.digTarget,adaptations:j.progress?.adaptations, held:j.progress?.held,collected:j.progress?.collected,area:j.progress?.area, item:j.progress?.item, phase:j.progress?.phase,target:j.progress?.target,approaches:j.progress?.approaches,missing:j.progress?.missing, blocked:j.progress?.blocked, usingItem:j.progress?.usingItem,
      nativeTask:j.progress?.nativeTask,route:j.progress?.movement?.route,scaffolding:j.progress?.scaffolding,scaffoldCleanup:j.progress?.scaffoldCleanup,result:j.result}))
    let changed = false
    // Fast actions can start and finish between 200 ms sampler ticks. Their
    // terminal record still owes the agent one event, even if running was never
    // sampled. Persisting the record makes repeated samples/restarts idempotent.
    for(const next of compact) {
      const old=jobs.find(j=>j.id===next.id)
      if(next.kind==='flee'&&next.status==='running'&&next.escape?.revision>0&&next.escape.revision!==old?.escape?.revision) {
        append('flee_state',{actionId:next.id,phase:next.phase,targets:next.targets,entityId:next.entityId,entityUuid:next.entityUuid,...next.escape})
        changed=true
      }
      if(!jobs.some(old=>old.id===next.id)&&!['running','cancelling'].includes(next.status)&&next.reason!=='action_replaced') {
        append(['completed','partial'].includes(next.status)?'job_finished':'job_blocked',{job:next})
        changed=true
      }
    }
    for (const old of jobs) {
      const next = compact.find(j => j.id === old.id)
      if (next?.reason==='action_replaced')continue
      if (active(old) && (!next || !active(next) || (next.status === 'paused' && old.status !== 'paused'))) {
        append(interrupted || !next ? 'job_interrupted' : ['completed','partial'].includes(next.status) ? 'job_finished' : 'job_blocked',
          {job:next || {...old, status:'interrupted', reason:'controller_restarted'}})
        changed = true
      }
    }
    if (JSON.stringify(jobs) !== JSON.stringify(compact)) {
      jobs = compact
      if (latest) latest.jobs = jobs.filter(active).concat(jobs.filter(j=>!active(j)).slice(-2))
      save(); wake()
    } else if (changed) {save(); wake()}
    // The jobs a finished defense call started are now recorded.
    defenseSettling = false
  }
  function disconnected(reason) {
    if (!bind() || session().intent !== 'playing') return
    if (latest?.clientError) return
    freshFrame = true
    stored.awaitingReady=true
    latest = {...(latest || world({},session(),jobs)), connected:false, ready:false,
      clientError:reason, observedAt:new Date(now()).toISOString()}
    save(); wake()
  }
  function ack(id, batchId) {
    own(id)
    if (batchId === stored.lastAck) return {acknowledged:batchId}
    if (!stored.offered || stored.offered.id !== batchId) throw new Error('batch_not_offered')
    const chats = stored.offered.events.filter(e => e.kind === 'chat')
    if (chats.length) {
      const last = chats.at(-1)
      const cursor = {session:last.clientEpoch, seq:last.message.seq}
      const ackFile = path.join(dataDir,'chat-ack.json')
      fs.writeFileSync(ackFile+'.tmp',JSON.stringify(cursor),{mode:0o600}); fs.renameSync(ackFile+'.tmp',ackFile)
    }
    stored.ack = stored.offered.through
    stored.deliveredRevision = stored.offered.revision
    stored.events = stored.events.filter(e => e.sequence > stored.ack)
    if (stored.gap && stored.gap.through <= stored.ack) stored.gap = null
    response.delivered(stored,stored.offered)
    stored.lastAck = batchId; stored.offered = null
    urgentAt = stored.events.length ? now() + urgentMs : null
    save(); wake(); return {acknowledged:batchId}
  }
  async function next(id, timeoutMs = 30000) {
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 0 || timeoutMs > 60000) throw new Error('timeoutMs must be 0..60000')
    const deadline = now() + timeoutMs
    for (;;) {
      const current = own(id)
      if (current.intent === 'ended') return {ended:true, reason:current.endReason}
      if (stopped) throw new Error('controller_restarting')
      if (stored.offered) return {batch:copy(stored.offered)}
      // Quiet world updates stay internal. Recovery emits one ready-again edge;
      // the controller reconnects and the same player resumes its own work.
      const update = response.update(stored,latest,now())
      const pending = stored.events.length > 0 || update !== null
      const due = urgentAt ?? now()
      if (freshFrame && latest && pending && now() >= due) {
        const events = stored.events.slice(0,24)
        // A summary may jump over suppressed threat revisions, but never over
        // unsent chat or action results in the durable event queue.
        if (update && events.length === stored.events.length && !events.some(e => e.sequence >= update.sequence)) events.push(update)
        const batchId = crypto.randomUUID()
        // A new danger is urgent: after delivery, follow-up threat updates stay
        // coalesced until the player reads fresh state. While the player is
        // already attacking or fleeing, danger is an ordinary update.
        const defending = defenseCalls > 0 || defenseSettling || jobs.some(j => active(j) && ['attack','flee'].includes(j.kind))
        const offeredPriority = response.offered(stored,events,batchId,now())
        const priority = defending ? 'normal' : offeredPriority
        stored.offered = {id:batchId, playSessionId:current.id, generation:current.generation,
          offeredAt:new Date(now()).toISOString(),
          revision:stored.revision, priority,
          world:copy({...latest,life:stored.life??0,threatResponse:response.view(stored,latest)}),
          events:copy(events), through:events.at(-1)?.sequence ?? stored.ack,
          ...(stored.gap ? {gap:stored.gap} : {})}
        stored.lastDelivery = now(); save()
        return {batch:copy(stored.offered)}
      }
      if (now() >= deadline) return {batch:null}
      await new Promise(resolve => {
        const finish = () => {clearTimeout(timer); waiters.delete(finish); resolve()}
        const timer = setTimeout(finish, Math.min(1000, deadline-now(), pending ? Math.max(1,due-now()) : 1000))
        waiters.add(finish)
      })
    }
  }
  function snapshot(id, presenting = false) {
    own(id)
    if (!latest) return null
    const threatResponse = presenting ? response.present(stored,latest) : response.view(stored,latest)
    if (presenting) {save();wake()}
    return {world:copy({...latest,life:stored.life??0,threatResponse}),events:[],priority:'snapshot'}
  }
  return {capture, updateJobs, disconnected, ack, next, wake, current:snapshot,
    // Until the next job sample, a finished defense call still protects the job
    // it started.
    defenseCall:delta => {defenseCalls = Math.max(0, defenseCalls+delta); if (delta < 0) defenseSettling = true},
    // Events recorded after the last offered batch; nobody has seen them yet.
    pending:id => {own(id); return stored.events.filter(e => e.sequence > (stored.offered ? stored.offered.through : stored.ack))},
    // Resolves on the next state change or event, or after ms.
    changed:ms => new Promise(resolve => {
      const finish = () => {clearTimeout(timer); waiters.delete(finish); resolve()}
      const timer = setTimeout(finish, ms)
      waiters.add(finish)
    }),
    stop:() => {stopped = true; wake()},
  }
}
