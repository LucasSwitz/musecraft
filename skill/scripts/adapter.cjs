// Commands act on the visible Java player. Each invocation owns a session fence.
const net = require('node:net')
const path = require('node:path')
const fs = require('node:fs')
const release = require('../release.json')
const playSession = require('./play-session.cjs')
const { AsyncLocalStorage } = require('node:async_hooks')
module.exports = function ({ cfg, getContext = () => null }) {
  const jobContext = new AsyncLocalStorage()
  let jobs
  let queue = Promise.resolve(), controlQueue = Promise.resolve(), owner = null, active = null
  const socketPath = path.join(cfg.dataDir, 'client.sock')
  const writes = new Set(['lookAt', 'equip', 'chat', 'animate', 'creativeItem', 'moveToHotbar', 'setFlying', 'placeAt', 'digAt', 'attackEntity', 'interactTarget', 'closeScreen', 'respawn', 'selectMaterial', 'selectTool', 'setHudHidden', 'moveStart', 'movePoll', 'selectItem', 'useItemStart', 'useItemPoll', 'digPoll', 'inventoryMove', 'inventorySwap', 'craftRecipe', 'craftBatchStart', 'craftBatchPoll', 'mountEntity', 'dismount', 'wake', 'selectWeapon', 'jumpPlaceStart', 'jumpPlacePoll', 'combatStart', 'combatQueue', 'fleeAdd', 'combatPoll', 'moveStop', 'buildStart', 'buildPoll', 'nativeTaskStart', 'nativeTaskPoll', 'protectArea'])
  const failure = (status, reason) => Object.assign(new Error(reason), { status })
  function rpc(method, params = {}, ctx = null, control = false) {
    const result = (control ? controlQueue : queue).then(() => {
      if (writes.has(method)) playSession.assertPlaying(cfg.dataDir, ctx?.playSession)
      if (writes.has(method) && (ctx?.signal?.aborted || ctx?.revoked)) throw failure('cancelled', 'invocation_cancelled')
      return new Promise((resolve, reject) => {
        const sock = net.createConnection(socketPath)
        let buffer = ''
        sock.setTimeout(5000, () => sock.destroy(failure('timed_out', 'client_response_timeout')))
        sock.on('error', reject)
        sock.on('connect', () => sock.write(JSON.stringify({ method, params, version: release.version, protocol: release.protocol, session: ctx?.session, playSession: ctx?.playSession }) + '\n'))
        sock.on('data', chunk => {
          buffer += chunk
          if (buffer.length > 1024 * 1024) return sock.destroy(new Error('Client response too large'))
          if (!buffer.includes('\n')) return
          sock.end()
          try {
            const response = JSON.parse(buffer.split('\n')[0])
            if (response.ok) {
              const actual = response.compatibility
              if (method !== 'stop' && (!actual || actual.version !== release.version || actual.protocol !== release.protocol || actual.minecraft !== release.minecraft || actual.fabricLoader !== release.fabricLoader)) {
                reject(failure('blocked', `musecraft_version_mismatch: controller=${release.version}; mod=${actual?.version || 'legacy'}; install the matching release and restart Minecraft`))
              } else if(method==='digPoll'&&response.value.status==='blocked') {
                // All mining callers share terminal failure semantics, including
                // construction/scaffold loops that still verify final geometry.
                reject(Object.assign(failure('blocked',response.value.reason),{blocker:response.value.evidence}))
              } else resolve(response.value)
            }
            else reject(failure(/session_changed/.test(response.error) ? 'cancelled' : 'blocked', response.error))
          } catch (e) { reject(e) }
        })
        sock.on('end', () => { if (!buffer.includes('\n')) reject(failure('blocked', 'client_disconnected')) })
      })
    })
    if (control) controlQueue = result.catch(() => {})
    else queue = result.catch(() => {})
    return result
  }
  async function releaseOwner(ctx) {
    ctx.revoked = true
    if (owner !== ctx) return
    try { await rpc('stop') }
    catch { await new Promise(r => setTimeout(r, 5000)) } // Mod key/mining TTL.
    finally { if (owner === ctx) owner = null }
  }
  async function deadman() {
    jobs?.abortAll()
    if (owner) owner.revoked = true
    try { await rpc('stop'); return { confirmed: true } }
    catch { return { confirmed: false, reason: 'client_unreachable; controls expire within 5 seconds of client ticking' } }
    finally { owner = null }
  }
  async function observe({ chatAfter = null } = {}) {
    let runtime = null
    try { const {bootId, recoveryOccurred, restarts, updatedAt} = JSON.parse(fs.readFileSync(path.join(cfg.dataDir,'runtime-status.json'),'utf8')); runtime={bootId,recoveryOccurred,restarts,updatedAt} } catch {}
    let doc
    try { doc = await rpc('observe', {chatAfter}) } catch (e) {
      const session = playSession.read(cfg.dataDir)
      if (!session) throw e
      doc = {ready:false,connected:false,clientError:e.message,chat:{messages:[]}}
    }
    return { ...doc, playSession:playSession.read(cfg.dataDir), controlOwner: owner?.name || null, action: active?.name || null, jobs: jobs?.list() || [], runtime }
  }
  async function perform(name, body, { allowScreen = false } = {}) {
    const ctx = jobContext.getStore() || getContext()
    if (!ctx) throw new Error('Control requires a invocation context')
    const started = Date.now()
    const result = (status, reason, observed = null) => ({ action: name, status, reason, elapsedMs: Date.now() - started, observed })
    try { playSession.assertPlaying(cfg.dataDir, ctx.playSession) } catch (e) { return result('cancelled',e.message) }
    if (ctx.signal?.aborted || ctx.revoked) return result('cancelled', 'invocation_cancelled')
    if (owner && owner !== ctx) return result('blocked', `control_owned_by:${owner.name}`)
    if (active) return result('blocked', 'action_in_progress; await the previous action')
    owner = ctx
    const token = { name, ctx }; active = token
    let last = null
    async function check() {
      playSession.assertPlaying(cfg.dataDir, ctx.playSession)
      if (ctx.signal?.aborted || ctx.revoked || owner !== ctx) throw failure('cancelled', 'invocation_cancelled')
      last = await rpc('observe')
      if (!ctx.session) ctx.session = last.session
      if (last.session !== ctx.session) { ctx.revoked = true; throw failure('cancelled', 'client_session_changed') }
      if (!last.connected) throw failure('blocked', 'client_disconnected')
      if (!last.ready && !allowScreen) throw failure('blocked', `client_not_ready:${last.screen}`)
      return last
    }
    const io = {
      check,
      read: (method, params) => rpc(method, params),
      write: async (method, params) => { await check(); return rpc(method, params, ctx) },
      pause: async ms => { const end = Date.now() + ms; while (Date.now() < end) { await new Promise(r => setTimeout(r, Math.min(100, end - Date.now()))); if (ctx.signal?.aborted || ctx.revoked) throw failure('cancelled', 'invocation_cancelled') } },
      fail: failure,
      stop: () => rpc('stop'),
      restoreHud: hidden => rpc('setHudHidden',{hidden},{session:ctx.session,playSession:ctx.playSession}),
    }
    try {
      await check()
      const evidence = await body(io)
      await check()
      return result('completed', 'observed', evidence ?? last)
    } catch (e) {
      return {...result(e.status || 'blocked', e.code || e.message, last),...(e.buildResult?{buildResult:e.buildResult}:{}),...(e.navigation?{navigation:e.navigation}:{}),...(e.blocker?{blocker:e.blocker}:{})}
    } finally {
      // Clear controls before another action can acquire ownership.
      if (owner === ctx) await rpc('stop').catch(() => {})
      if (active === token) active = null
    }
  }
  const { mc: rawMc } = require('./actions.cjs')({ perform, observe, rpc })
  const mc = {...rawMc}
  mc.respawn = async () => {
    await Promise.all(jobs.list().filter(j => ['running','paused','cancelling'].includes(j.status)).map(j => jobs.api.jobs.cancel(j.id)))
    return perform('respawn', async io => {
      const before = await io.check()
      if (before.dead || before.health <= 0) await io.write('respawn')
      const confirmationUntil = Date.now() + 10000
      for (;;) {
        const state = await io.check()
        if (state.ready && !state.dead && state.health > 0) return {confirmation:'alive_observed',player:state.player,pos:state.pos,health:state.health}
        if (Date.now() >= confirmationUntil) throw io.fail('blocked','respawn_not_confirmed')
        await io.pause(100)
      }
    }, {allowScreen:true})
  }
  mc.closeScreen = () => perform('closeScreen', async io => {
    await io.write('closeScreen'); await io.pause(100)
    const state=await io.check()
    return {ready:state.ready,screen:state.screen,confirmation:state.screen===null?'screen_closed':'screen_open'}
  }, { allowScreen: true })
  // Chat does not acquire movement ownership or clear another job's controls.
  mc.chat = async text => {
    const current = getContext()
    if (!current || current.revoked || current.signal?.aborted) throw failure('cancelled','invocation_cancelled')
    const doc = await rpc('observe')
    if (!doc.connected) throw failure('blocked','client_disconnected')
    const ctx = {session:doc.session,signal:current.signal,playSession:current.playSession}
    await rpc('chat',{text},ctx)
    return {action:'chat',status:'completed',reason:'submitted',observed:{submitted:true}}
  }
  jobs = require('./jobs.cjs')({
    mc, observe, addFlee: (params,ctx) => rpc('fleeAdd',params,ctx,true), queueCombat: (params,ctx) => rpc('combatQueue',params,ctx,true), dataDir: cfg.dataDir, getPlaySession: () => getContext()?.playSession,
    launch: async (ctx, name, body, allowScreen) => {
      ctx.playSession = getContext()?.playSession
      playSession.assertPlaying(cfg.dataDir,ctx.playSession)
      // Reserve synchronously before the invocation can submit another action.
      if (active || (owner && owner !== getContext())) throw failure('blocked','control_busy')
      owner = ctx
      try { return await jobContext.run(ctx, () => perform(name, body, {allowScreen})) }
      finally { await releaseOwner(ctx) }
    },
  })
  Object.assign(mc, jobs.api)
  return { prepareFlee: params => rpc('fleeCheck',params), controlRpc: (method, params, ctx) => rpc(method, params, ctx, true), rpc, mc, screen: { capture: () => rpc('screenshot') },
    observe, releaseOwner, deadman }
}
