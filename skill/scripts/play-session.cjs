// Controller-owned play intent. Network state never decides whether play has ended.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const copy = value => value == null ? null : JSON.parse(JSON.stringify(value))
const fileFor = dir => path.join(dir, 'play-session.json')
function read(dir) {
  try { return JSON.parse(fs.readFileSync(fileFor(dir), 'utf8')) }
  catch (e) { if (e.code === 'ENOENT') return null; throw e }
}
function assertPlaying(dir, id) {
  const state = read(dir)
  if (!state && !id) return // Existing interactive use without a play session.
  if (!state || !id || state.id !== id) throw new Error('play_session_changed: run `musecraft play` to resume')
  if (state.intent !== 'playing') throw new Error('play_session_ended')
  if (state.status === 'blocked') throw new Error(`play_session_blocked: ${state.error}`)
  return state
}
// The owner is the Minecraft player whose requests the agent follows.
function checkOwner(owner) {
  if (owner !== undefined && !/^[a-zA-Z0-9_]{1,16}$/.test(owner)) throw new Error('owner must be a Minecraft player name')
}
module.exports = ({ dataDir, rpc, cancel, onDeath = () => {}, now = Date.now }) => {
  let state = read(dataDir), busy = false, respawnAfter = 0
  const history = path.join(dataDir, 'play-sessions')
  fs.mkdirSync(history, { recursive: true, mode: 0o700 })
  function save() {
    state.updatedAt = now()
    const filename = fileFor(dataDir), temporary = filename + '.tmp'
    const fd = fs.openSync(temporary, 'w', 0o600)
    try { fs.writeFileSync(fd, JSON.stringify(state)); fs.fsyncSync(fd) } finally { fs.closeSync(fd) }
    fs.renameSync(temporary, filename)
    const directory = fs.openSync(dataDir, 'r')
    try { fs.fsyncSync(directory) } finally { fs.closeSync(directory) }
  }
  const snapshot = () => copy(state)
  function own(id) {
    if (!state || state.id !== id) throw new Error('play_session_changed')
    return state
  }
  function start(options) {
    const id = options.id || crypto.randomUUID()
    if (!/^[a-zA-Z0-9_-]{1,100}$/.test(id)) throw new Error('Invalid play session id')
    if (state?.id === id) {
      if (state.server !== options.server || state.player !== options.player) throw new Error('play_session_definition_changed')
      return snapshot() // Replaying start cannot revive a terminal session.
    }
    if (state?.intent === 'playing') throw new Error('A play session is already active; end it first')
    if (fs.existsSync(path.join(history, id + '.json'))) throw new Error('play_session_id_already_used')
    if (typeof options.server !== 'string' || !options.server.trim() || /[\s/\\]/.test(options.server) || options.server.length > 255) throw new Error('server must be a Minecraft hostname or IP with optional port')
    if (!/^[a-zA-Z0-9_]{1,16}$/.test(options.player || '')) throw new Error('player must be the licensed Java profile name')
    checkOwner(options.owner)
    if (state) fs.writeFileSync(path.join(history, state.id + '.json'), JSON.stringify(state), { mode: 0o600 })
    state = { id, generation: (state?.generation || 0) + 1, server: options.server, player: options.player,
      owner: options.owner ?? null, intent: 'playing', status: 'connecting', endReason: null, attempts: 0,
      error: null, nextAttemptAt: 0, startedAt: now(), cleanupPending: false }
    save(); return snapshot()
  }
  // Start play, or resume the active session after a restart or a blocker.
  function resume(options) {
    if (state?.intent !== 'playing') return start(options)
    if (state.server !== options.server || state.player !== options.player) throw new Error('play_session_definition_changed')
    checkOwner(options.owner)
    state.owner = options.owner ?? state.owner ?? null
    if (!['playing','connecting','recovering'].includes(state.status)) {
      state.status = 'recovering'; state.error = null; state.attempts = 0; state.nextAttemptAt = 0
    }
    save(); return snapshot()
  }
  function end(id, reason) {
    own(id)
    if (state.intent === 'ended') return snapshot()
    if (typeof reason !== 'string' || !reason.trim() || reason.length > 1000) throw new Error('A short end reason is required')
    state.intent = 'ended'; state.status = 'stopped'; state.endReason = reason
    state.endedAt = now(); state.cleanupPending = true; save() // Persist before any asynchronous cleanup.
    cancel()
    return snapshot()
  }
  function retry(id) {
    own(id)
    if (state.intent !== 'playing') throw new Error('play_session_ended')
    state.status = 'recovering'; state.error = null; state.attempts = 0; state.nextAttemptAt = 0; save(); return snapshot()
  }
  async function tick() {
    if (busy || !state) return
    busy = true
    const current = state
    const mayPlay = () => state === current && current.intent === 'playing' && current.status !== 'blocked'
    try {
      const active = current.intent === 'playing' && current.status !== 'blocked'
      await rpc('playPolicy', { id: current.id, generation: current.generation, active, ended: current.intent === 'ended', server: current.server, player: current.player })
      if (state !== current) return
      if (current.intent === 'ended') {
        const closed = await rpc('observe')
        if (closed.connected) return
        if (current.cleanupPending) { current.cleanupPending = false; save() }
        return
      }
      if (current.status === 'blocked') return
      const doc = await rpc('observe')
      if (!mayPlay()) return
      if (doc.connected) {
        if (doc.player !== current.player || doc.server?.replace(/:25565$/, '') !== current.server.replace(/:25565$/, '')) {
          current.status = 'blocked'; current.error = 'wrong_player_or_server'; cancel(); save(); return
        }
        if (doc.dead && now() >= respawnAfter) {
          onDeath(doc) // Persist before respawn can hide death from the stream.
          cancel(); respawnAfter = now() + 5000
          await rpc('respawn', {}, {session:doc.session,playSession:current.id})
          if (!mayPlay()) return
        }
        if (current.status !== 'playing') { current.status = 'playing'; current.error = null; current.attempts = 0; current.nextAttemptAt = 0; save() }
        return
      }
      const reason = doc.connection?.reason || null
      if (reason && /banned|white.?list|not authenticated|invalid session|expired|incompatible|outdated|not allowed|kicked/i.test(reason)) {
        current.status = 'blocked'; current.error = reason; cancel(); save(); return
      }
      if (current.status === 'playing') { current.status = 'recovering'; current.error = reason; save(); cancel() }
      if (doc.connection?.connecting || now() < current.nextAttemptAt) return
      const connection = await rpc('connectServer', { id: current.id })
      if (!mayPlay() || !connection.submitted) return
      current.status = current.attempts ? 'recovering' : 'connecting'
      current.attempts++; current.nextAttemptAt = now() + Math.min(60000, 2000 * 2 ** Math.min(5, current.attempts - 1)); save()
    } catch (e) {
      if (mayPlay() && current.error !== e.message) {
        current.error = e.message; current.status = /wrong_licensed_player|version_mismatch|hardcore_respawn_unsupported/.test(e.message) ? 'blocked' : 'recovering'; save()
      }
    } finally { busy = false }
  }
  return { start, resume, end, retry, snapshot, tick }
}
module.exports.read = read
module.exports.assertPlaying = assertPlaying
