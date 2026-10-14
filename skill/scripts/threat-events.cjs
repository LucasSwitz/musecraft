// Persist encounters, not every movement frame. A brief look away is the same encounter.
module.exports = function captureThreats(stored, doc, now, event) {
  if (!doc.connected) return false
  let changed = false
  if (stored.threatEpoch !== doc.session) {
    stored.threatEpoch = doc.session
    stored.threats = {}
    changed = true
  }
  const seen = new Set(), entered = [], escalated = []
  for (const entity of doc.visibleHostiles?.entities || []) {
    if (!entity.uuid || !entity.alive || !entity.lineOfSight || !entity.inFieldOfView) continue
    seen.add(entity.uuid)
    let prior = stored.threats[entity.uuid]
    const reentered=prior&&prior.missingSince!==null&&now-prior.missingSince>=10000
    if (!prior || reentered) entered.push(entity)
    if (!prior || prior.missingSince !== null) {
      prior = stored.threats[entity.uuid] = {...prior,missingSince:null,level:reentered?0:prior?.level??0}
      changed = true
    }
    const level=entity.threat?.level??0
    if(level>(prior.level??0)) {
      escalated.push(entity);prior.level=level;prior.calmSince=null;changed=true
    } else if(level<(prior.level??0)) {
      // Rearm only after sustained lower danger, not a jitter across a radius.
      if(prior.calmSince==null){prior.calmSince=now;changed=true}
      else if(now-prior.calmSince>=10000){prior.level=level;prior.calmSince=null;changed=true}
    } else if(prior.calmSince!=null){prior.calmSince=null;changed=true}
  }
  for (const [uuid, prior] of Object.entries(stored.threats)) if (!seen.has(uuid)) {
    if (prior.missingSince === null) {prior.missingSince=now;changed=true}
    else if (now-prior.missingSince >= 60000) {delete stored.threats[uuid];changed=true}
  }
  if (entered.length) event('hostile_visible', {entities:entered,
    range:doc.visibleHostiles.range,fieldOfViewDegrees:doc.visibleHostiles.fieldOfViewDegrees,
    omitted:doc.visibleHostiles.omitted})
  if(escalated.length)event('threat_escalated',{entities:escalated,
    targetingPlayer:'unconfirmed',health:doc.health,selectedItem:doc.selectedItem,
    nearbyThreats:doc.visibleHostiles.entities.filter(e=>!escalated.some(t=>t.uuid===e.uuid)).slice(0,8)})
  return changed
}
