// First contact is immediate. A hit stream must leave time to choose a response.
module.exports = function captureDamage(stored, doc, previousHealth, now, event) {
  if (stored.damageEpoch !== doc.session) {
    stored.damageEpoch=doc.session;stored.damageSequence=doc.damage.sequence
    stored.lastDamageAt=null;stored.lastDamageReportAt=null;stored.pendingDamage=null
    return true
  }
  let changed=false
  function emit(immediate, critical = false) {
    const damage=stored.pendingDamage
    event('damaged',{immediate,critical,hits:damage.hits,amount:damage.unknownAmount?null:damage.amount,
      attacker:damage.attacker,damageObservedAt:damage.observedAt})
    stored.pendingDamage=null;stored.lastDamageReportAt=now;changed=true
  }
  if (doc.damage.sequence>stored.damageSequence) {
    const first=stored.lastDamageAt==null || now-stored.lastDamageAt>=10000
    const critical=previousHealth>6 && doc.health<=6
    const damage=stored.pendingDamage??={hits:0,amount:0,unknownAmount:false}
    damage.hits+=doc.damage.sequence-stored.damageSequence
    if(previousHealth>doc.health)damage.amount+=previousHealth-doc.health
    else damage.unknownAmount=true
    damage.attacker=doc.damage.attacker??null;damage.observedAt=doc.damage.observedAt
    stored.damageSequence=doc.damage.sequence;stored.lastDamageAt=now;changed=true
    if(first || critical)emit(true,critical)
  }
  // Health and damage packets can land in different observation frames.
  if(stored.pendingDamage && previousHealth>6 && doc.health<=6)emit(true,true)
  if(stored.pendingDamage && now-stored.lastDamageReportAt>=10000)emit(false)
  return changed
}
