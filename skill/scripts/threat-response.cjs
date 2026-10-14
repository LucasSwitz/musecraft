// Danger arbitration: one urgent decision per encounter; later threat changes are coalesced.
const threatKinds = new Set(['hostile_visible','threat_escalated','damaged'])
function state(stored, epoch) {
  if (!stored.threatResponse || stored.threatResponse.epoch !== epoch) {
    stored.threatResponse = {epoch, cycle:0, through:0, presentedThrough:0, notifiedThrough:0,
      awaiting:false, notificationOutstanding:false, trigger:null,
      hits:0, damage:0, unknownDamage:false, lastDamageAt:null, calmSince:null, audit:[]}
  }
  return stored.threatResponse
}
function audit(s, value, now) {
  s.audit.push({at:new Date(now).toISOString(),...value})
  s.audit = s.audit.slice(-64)
}
function trigger(s, event, now) {
  s.cycle++
  s.awaiting = true
  s.trigger = {sequence:event.sequence, kind:event.kind, at:event.observedAt}
  audit(s, {kind:'decision_requested',cycle:s.cycle,through:event.sequence,reason:event.kind}, now)
}
function capture(stored, event, world, now) {
  const s = state(stored, world?.clientEpoch ?? null)
  if (!threatKinds.has(event.kind)) return true
  s.through = event.sequence
  if (event.kind === 'damaged') {
    s.hits += event.hits ?? 1
    s.damage += event.amount ?? 0
    s.unknownDamage ||= event.amount == null
    s.lastDamageAt = now
    s.attacker = event.attacker ?? null
  }
  const danger = event.kind === 'threat_escalated' || event.kind === 'damaged' && event.immediate !== false
  if (danger && !s.awaiting) {
    trigger(s, event, now)
    return true
  }
  // Keep subsequent threat changes in one replaceable snapshot. next() offers
  // at most one normal update until the player reads fresh state.
  return !s.awaiting && !s.notificationOutstanding
}
function refresh(stored, world, now) {
  const s = state(stored, world.clientEpoch)
  const before = [s.awaiting,s.calmSince]
  const dangerous = (world.visibleHostiles?.entities || []).some(e => e.alive && e.lineOfSight && (e.threat?.level ?? 0) > 0)
  if (dangerous) s.calmSince = null
  else if (s.calmSince === null) s.calmSince = now
  if (s.calmSince !== null && now-s.calmSince >= 10000
      && (s.lastDamageAt === null || now-s.lastDamageAt >= 10000) && s.awaiting) {
    s.awaiting = false; s.trigger = null
    s.hits = 0; s.damage = 0; s.unknownDamage = false; s.attacker = null
    audit(s, {kind:'encounter_cleared',cycle:s.cycle}, now)
  }
  return before[0] !== s.awaiting || before[1] !== s.calmSince
}
function view(stored, world) {
  const s = state(stored, world?.clientEpoch ?? null)
  const entities = world?.visibleHostiles?.entities || []
  const attacker = stored.pendingDamage?.attacker ?? s.attacker
  return {receipt:{epoch:s.epoch,cycle:s.cycle,through:s.through},
    awaitingResponse:s.awaiting, trigger:s.trigger,
    health:world?.player?.health ?? null, observedAt:world?.observedAt ?? null,
    damage:{hits:s.hits+(stored.pendingDamage?.hits??0),
      amount:s.unknownDamage || stored.pendingDamage?.unknownAmount ? null : s.damage+(stored.pendingDamage?.amount??0),
      attacker:attacker?{id:attacker.id,uuid:attacker.uuid,type:attacker.type,pos:attacker.pos}:null},
    entities:entities.slice(0,8).map(e => ({id:e.id,uuid:e.uuid,type:e.type,pos:e.pos,
      distance:e.distance,threat:e.threat,lineOfSight:e.lineOfSight,inFieldOfView:e.inFieldOfView})),
    omitted:Math.max(0,entities.length-8)+(world?.visibleHostiles?.omitted ?? 0)}
}
function update(stored, world, now) {
  const s = state(stored, world?.clientEpoch ?? null)
  // The urgent decision already covers this encounter. Keep damage samples in
  // the latest state and command results instead of repeating events.
  if (s.awaiting) return null
  if (s.notificationOutstanding || s.through <= Math.max(s.notifiedThrough,s.presentedThrough)) return null
  return {id:`${stored.sessionId}:${stored.generation}:threat:${s.through}`,sequence:s.through,
    kind:'threat_update',observedAt:new Date(now).toISOString(),clientEpoch:s.epoch,...view(stored,world)}
}
function offered(stored, events, id, now) {
  const s = stored.threatResponse
  if (!s) return 'normal'
  // This exact snapshot travels with the durable input. Its receipt can be
  // used by a tool before the producer's ACK returns; no extra turn read is
  // needed to authorize it. Offering alone never acknowledges a decision.
  s.presentedThrough = Math.max(s.presentedThrough,s.through)
  const urgent = s.awaiting && events.some(e => e.sequence === s.trigger?.sequence && e.kind === s.trigger.kind)
  const threats = events.filter(e => threatKinds.has(e.kind) || e.kind === 'threat_update')
  if (threats.length || urgent) {
    s.notifiedThrough = Math.max(s.notifiedThrough,...events.map(e => e.sequence))
    s.notificationOutstanding = true
    audit(s, {kind:'notification',batchId:id,cycle:s.cycle,priority:urgent?'urgent':'normal',through:s.notifiedThrough}, now)
  }
  return urgent ? 'urgent' : 'normal'
}
function delivered(stored, batch) {
  const s = stored.threatResponse, seen = batch.world?.threatResponse?.receipt
  // Keep coalescing updates after an urgent delivery until the player reads
  // fresh state.
  if (batch.priority === 'urgent' && seen?.epoch === s?.epoch && seen?.cycle === s?.cycle)
    s.notificationOutstanding = true
}
function present(stored, world) {
  const value = view(stored,world), s = stored.threatResponse
  s.presentedThrough = s.through
  s.notificationOutstanding = false
  return value
}
module.exports = {capture,refresh,view,update,offered,delivered,present}
