// A day boundary is a decision opportunity; ordinary clock ticks stay quiet.
module.exports = (stored, doc, event) => {
  if (!doc.connected || doc.joining || doc.dimension !== 'minecraft:overworld'
      || !doc.server || !Number.isSafeInteger(doc.worldDay) || doc.worldDay < 0
      || !Number.isFinite(doc.timeOfDay) || doc.timeOfDay < 0 || doc.timeOfDay >= 24000) return false
  const cursor = stored.morningCursor
  if (!cursor || cursor.server !== doc.server) {
    // Joining during the day establishes a baseline, not another morning.
    stored.morningCursor = {server:doc.server, day:doc.worldDay, pendingDay:null}
    return true
  }
  let changed = false
  if (doc.worldDay > cursor.day) {
    // Coalesce missed days to the latest. A backwards clock adjustment cannot
    // announce a day twice. Persist this edge even while the player is asleep.
    cursor.day = doc.worldDay
    cursor.pendingDay = doc.worldDay
    changed = true
  }
  if (cursor.pendingDay !== null) {
    if (doc.worldDay !== cursor.pendingDay || doc.timeOfDay >= 12000) {
      cursor.pendingDay = null
      changed = true
    } else if (doc.ready && doc.sleeping === false && doc.dead === false && doc.health > 0) {
      event('morning', {day:cursor.pendingDay, timeOfDay:doc.timeOfDay})
      cursor.pendingDay = null
      changed = true
    }
  }
  return changed
}
