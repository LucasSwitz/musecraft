// CLI play: the agent types one command per decision. The controller supplies
// every identity, sequence and event cursor, and decides what is worth waking
// the player for. Idle play costs nothing: only events that need a decision
// end standby or an open command.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const {AsyncLocalStorage} = require('node:async_hooks')
const {tools} = require('./tool-api.cjs')
const {project, context, offer} = require('./turn-context.cjs')
// A turn still issuing commands sees new events in their output. Standby
// wakes the player only after this long without a command.
const COMMAND_SETTLE_MS = 1500
const LIFECYCLE = new Set(['died','respawned','ready','ready_again'])
// These return once started: follow and flee run until replaced, and combat
// must leave the player free to queue enemies and talk while it runs. Their
// outcomes still wake the player.
const RETURN_AT_ONCE = new Set(['mc.follow','mc.flee','mc.attack','mc.queueAttack'])
// No model reads output and issues a new command this fast, so a command
// that starts sooner after a command's output with waking events is the rest
// of the same shell chain: it must not act before the player has seen them.
// Standby is always its own exec, so nothing chains after it.
const CHAIN_MS = 1000
const CHAINED = 'Skipped: the command before this one in the same exec returned with events that need you (printed above). Read them and decide again.'

// The player's `musecraft client` command records its supervisor's PID.
function clientRunning(dataDir) {
  try { process.kill(Number(fs.readFileSync(path.join(dataDir, 'client-lifetime.pid'), 'utf8')), 0); return true }
  catch { return false }
}
const CLIENT_DOWN = 'The Minecraft client is not running. Start it with `musecraft client` and leave that command running in the background.'
const STANDBY_OFF = 'Standby is off: run `musecraft standby` in the background before you end your turn, or nothing wakes you.'
const short = value => String(value ?? 'unknown').replace(/^minecraft:/, '')

// Why an inbox event needs the player now, or null when it can wait for the
// next wake. The owner's chat and messages naming the player always wake it.
function wakeReason(event, session) {
  const owner = session.owner ? String(session.owner).toLowerCase() : null
  const named = name => owner !== null && String(name ?? '').toLowerCase() === owner
  switch (event.kind) {
    case 'chat': {
      const from = event.message?.senderName ?? 'a player'
      if (named(from)) return `chat from ${from}`
      if (String(event.message?.text ?? '').toLowerCase().includes(session.player.toLowerCase())) return `chat from ${from} naming you`
      return null
    }
    case 'damaged': return event.immediate === false ? null : 'you were hit'
    case 'threat_escalated': return 'danger nearby'
    case 'job_finished': return 'your action finished'
    case 'job_blocked': return 'your action is blocked'
    case 'job_interrupted': return 'your action was interrupted'
    case 'died': return 'you died'
    case 'respawned': return 'you respawned'
    case 'ready': return 'you joined the server'
    case 'ready_again': return 'you reconnected'
    case 'player_joined': return named(event.player?.name) ? `${event.player.name} joined` : null
    case 'player_left': return named(event.player?.name) ? `${event.player.name} left` : null
    default: return null
  }
}
// What the player reads: no delivery bookkeeping, and chat as sender and text.
function display(event) {
  const {id, sequence, clientEpoch, ...rest} = event
  if (rest.kind !== 'chat') return rest
  return {kind:'chat', from:rest.message?.senderName ?? null, text:rest.message?.text ?? '', at:rest.observedAt}
}
// One line instead of the full state: enough to answer and act on most events.
function status(view) {
  if (!view) return 'NOW state unavailable; run `musecraft state` once the client is connected.'
  const {player:p, connection:c, action, owner, entities, onlinePlayers} = view.state
  const near = owner && entities.items.find(e => e.type === 'minecraft:player' && e.name === owner)
  const online = owner && onlinePlayers?.players?.some(player => player.name === owner)
  const seen = !owner ? 'none set' : near ? `${owner}, entity ${near.id}, ${near.distance} blocks away`
    : online ? `${owner}, online but not nearby` : onlinePlayers ? `${owner}, offline` : owner
  return `NOW health ${p.health ?? '?'}/20, food ${p.food ?? '?'}/20, at ${JSON.stringify(p.pos)} in ${short(c.dimension)}; `
    + `${c.ready ? 'ready' : 'not ready'}; action: ${action ? short(action.name.replace(/^mc\./, '')) + ' ' + action.status : 'none'}; owner: ${seen}.`
}

const create = ({dataDir, play, inbox, invoke}) => {
  const file = path.join(dataDir, 'cli-state.json')
  let state = {}
  try { state = JSON.parse(fs.readFileSync(file, 'utf8')) } catch (e) { if (e.code !== 'ENOENT') throw e }
  let inFlight = 0, standing = 0
  const clientDown = () => {
    try { if (!JSON.parse(fs.readFileSync(path.join(dataDir, 'cli-config.json'), 'utf8')).client) return false }
    catch { return false }
    return !clientRunning(dataDir)
  }
  function save() {
    fs.writeFileSync(file + '.tmp', JSON.stringify(state), {mode:0o600})
    fs.renameSync(file + '.tmp', file)
  }
  function current() {
    const session = play.snapshot()
    if (!session || session.intent !== 'playing') throw new Error('not_playing: run `musecraft play` first')
    if (state.sessionId !== session.id || state.generation !== session.generation) {
      state = {sessionId:session.id, generation:session.generation, sequence:state.sequence ?? 0}
      save()
    }
    return session
  }
  // Printed output has reached the player by the time it acts again.
  function acknowledge(session) {
    if (!state.offered) return
    try { inbox.ack(session.id, state.offered) } catch (e) { if (e.message !== 'batch_not_offered') throw e }
    delete state.offered; save()
  }
  function frame(session, presenting = false) {
    const snapshot = project(inbox.current(session.id, presenting))
    return snapshot ? context(snapshot.world, [], new Date().toISOString()) : null
  }
  const caller = new AsyncLocalStorage()
  const aborted = () => caller.getStore()?.aborted
  async function nextBatch(session, timeoutMs) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (aborted()) return {}
      const next = await inbox.next(session.id, Math.max(0, Math.min(5000, deadline - Date.now())))
      if (aborted()) return {}
      if (next.ended) return {ended:next.reason}
      if (next.batch) return {batch:next.batch}
      if (Date.now() >= deadline) return {}
    }
  }
  // Deliver everything pending as one batch, after acknowledging the output
  // printed before it.
  async function collect(session, chainable) {
    acknowledge(session)
    if (!inbox.pending(session.id).length) return null
    const got = await nextBatch(session, 2000)
    if (!got.batch) return null
    const view = offer(got.batch)
    const waking = got.batch.events.some(event => wakeReason(event, play.snapshot() ?? session))
    state.offered = got.batch.id; state.identity = view.context.identity
    if (waking && chainable) state.wokeAt = Date.now()
    save()
    return {events:view.events, view:view.context}
  }
  // The first pending event that needs the player now. The owner may change
  // while a command is open.
  function needed(session) {
    const live = play.snapshot() ?? session
    for (const event of inbox.pending(session.id)) {
      const reason = wakeReason(event, live)
      if (reason) return reason
    }
    return null
  }
  // Wait until `until` gives a reason, play stops, the caller leaves or time runs out.
  async function watch(session, timeoutMs, until) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (aborted()) return {}
      const now = play.snapshot()
      if (now?.id !== session.id || now.intent !== 'playing') return {ended:now?.endReason || 'play stopped'}
      const reason = until()
      if (reason) return {reason}
      const left = deadline - Date.now()
      if (left <= 0) return {}
      await inbox.changed(Math.min(500, left))
    }
  }
  // Result, new events and status. The full state follows joining, death and
  // reconnection, and `musecraft state`; otherwise one status line.
  function render({result, note, events = [], view, full = false}) {
    const lines = []
    if (result !== undefined) lines.push('RESULT ' + JSON.stringify(result))
    if (note) lines.push(note)
    // Only the latest morning matters after a long quiet stretch.
    const shown = events.filter((e, i) => e.kind !== 'morning' || !events.slice(i + 1).some(later => later.kind === 'morning'))
    if (shown.length) {
      lines.push('EVENTS (oldest first):')
      for (const event of shown) lines.push('- ' + JSON.stringify(display(event)))
    }
    lines.push(full || events.some(e => LIFECYCLE.has(e.kind)) ? (view?.message ?? 'State unavailable.') : status(view))
    if (clientDown()) lines.push(CLIENT_DOWN)
    if (!standing) lines.push(STANDBY_OFF)
    return lines.join('\n')
  }
  async function report(session, {result, note, full = false, chainable = true} = {}) {
    const delivered = await collect(session, chainable)
    const view = full || !delivered ? frame(session, full) : delivered.view
    if (full) { state.identity = view?.identity; save() }
    return {text:render({result, note, events:delivered?.events, view, full})}
  }
  const chained = () => Date.now() - (state.wokeAt ?? 0) < CHAIN_MS
  // Every invocation needs a sequence above any earlier one.
  function nextSequence() {
    state.sequence = Math.max((state.sequence ?? 0) + 1, Date.now()); save()
    return state.sequence
  }
  async function act(method, input) {
    const session = current()
    acknowledge(session)
    const tool = tools[method]
    if (!tool) throw new Error(`unknown_action ${method}: run \`musecraft help\``)
    if (tool.action && chained()) return {text:render({result:{ok:false, action:method, status:'skipped', reason:'events_need_review'}, note:CHAINED, view:frame(session)})}
    if (!state.identity) {
      const view = frame(session, true)
      if (!view) throw new Error(clientDown() ? 'world_snapshot_unavailable: ' + CLIENT_DOWN : 'world_snapshot_unavailable: the client is not connected yet; run `musecraft wait`')
      state.identity = view.identity
    }
    let result
    try {
      result = await invoke({playSession:session.id, generation:session.generation, method, input:input ?? {},
        callId:crypto.randomUUID(), sequence:nextSequence(), world:state.identity})
    } catch (e) { result = {ok:false, action:method, status:'blocked', reason:e.message} }
    // A decision made from an outdated view: show the fresh view instead.
    if (/observed_world_changed|observed_life_changed/.test(result?.reason ?? '')) return report(session, {result, full:true})
    if (method === 'mc.endSession') return {text:render({result, view:frame(session)})}
    // A long action keeps its command open until something needs the player.
    if (tool.action && result?.status === 'running' && !RETURN_AT_ONCE.has(method)) {
      const got = await watch(session, 15 * 60000, () => needed(session))
      if (got.ended) return {text:render({result, note:'Play ended: ' + got.ended})}
      return report(session, {result, note:got.reason ? null : 'Still running after 15 minutes; it continues and its outcome will wake you.'})
    }
    // A short action's outcome event lands within a sampler tick: report it
    // here so it does not wake the player again after this turn.
    if (tool.action) await watch(session, 700, () => inbox.pending(session.id).some(e => /^job_/.test(e.kind)) ? 'reported' : null)
    return report(session, {result})
  }
  async function wait(seconds) {
    const session = current()
    if (chained()) return {text:render({note:CHAINED, view:frame(session)})}
    acknowledge(session)
    const limit = Math.min(3600, Math.max(1, Number(seconds) || 300))
    const got = await watch(session, limit * 1000, () => clientDown() ? 'the game client is not running' : needed(session))
    if (got.ended) return {text:render({note:'Play ended: ' + got.ended})}
    return report(session, {note:(got.reason ? `Woke: ${got.reason}.` : `Nothing needed you in ${limit} seconds.`) + joinHint()})
  }
  // Muse silently drops direct TCP, game traffic included, until the user
  // allows it, so a join that keeps failing usually needs the user.
  function joinHint() {
    const now = play.snapshot()
    if (!now || now.intent !== 'playing' || now.status === 'playing' || (now.attempts ?? 0) < 3) return ''
    return ` Still not connected after ${now.attempts} attempts${now.error ? ` (${now.error})` : ''}. On Muse, ask the user to open Muse settings → Permissions → Direct network protocols, switch Other TCP connections to Ask, and choose Always allow when the server's approval appears.`
  }
  async function start(config) {
    const session = play.resume(config)
    state = {sessionId:session.id, generation:session.generation, sequence:state.sequence ?? 0}; save()
    const deadline = Date.now() + 90000
    while (Date.now() < deadline && !clientDown()) {
      const now = play.snapshot()
      if (now.status === 'blocked') throw new Error('play_blocked: ' + now.error)
      if (now.status === 'playing' && inbox.current(now.id)) break
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
    if (!inbox.current(session.id)) return {text:clientDown()
      ? `Saved play on ${config.server}. ${CLIENT_DOWN} Then run \`musecraft wait\` until you join, and start standby.`
      : `Joining ${config.server}; status ${play.snapshot().status}.${joinHint()} Run \`musecraft wait\`.`}
    return report(session, {note:`Playing on ${config.server} as ${config.player} with ${config.owner}.`, full:true})
  }
  function show() {
    const session = current()
    return report(session, {full:true})
  }
  // The player keeps this command running in the background between turns;
  // it exits only to wake them, so its completion is the wake-up, and its
  // output carries the events that caused it.
  async function standby() {
    // One standby is enough; extra ones would pile up until the next wake.
    if (standing) return {text:'Standby is already running in another background command, so this one exits. This is not a wake-up: keep playing.'}
    standing++
    try {
      const session = play.snapshot()
      if (session?.intent !== 'playing') return {text:'Play is not active, so standby ended.'}
      const got = await watch(session, Infinity, () => {
        if (inFlight || Date.now() - (state.lastCallEndAt ?? 0) < COMMAND_SETTLE_MS) return null
        if (clientDown()) return 'the game client is not running'
        return needed(session)
      })
      if (aborted()) return {text:'Standby cancelled.'}
      if (got.ended) return {text:'Play is not active, so standby ended.'}
      // Await here so this standby still counts as running while its output renders.
      return await report(session, {note:`Minecraft needs you: ${got.reason}. Act on it, then restart standby before you end your turn.`, chainable:false})
    } finally { standing-- }
  }
  return async body => {
    // Standby is not a player command: it must not look like activity.
    if (body.verb === 'standby') return caller.run(body.signal, standby)
    inFlight++
    try {
      return await caller.run(body.signal, () => {
        if (body.verb === 'play') return start(body.input)
        if (body.verb === 'state') return show()
        if (body.verb === 'wait') return wait(body.input?.seconds)
        if (body.verb === 'stop') return act('mc.endSession', {reason:body.input?.reason || 'player asked to stop'})
        if (body.verb === 'act') return act(body.method, body.input)
        throw new Error('unknown verb')
      })
    } finally {
      inFlight--
      state.lastCallEndAt = Date.now(); save()
    }
  }
}
module.exports = Object.assign(create, {clientRunning, wakeReason})
