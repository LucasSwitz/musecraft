// Agent-facing commands: `musecraft <action> [key=value ...]`. Everything the
// game loop needs besides the decision itself lives in the controller.
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const {spawn, spawnSync} = require('node:child_process')
const {tools, validate} = require('./tool-api.cjs')
const {clientRunning} = require('./cli-session.cjs')
const dataDir = process.env.MUSECRAFT_HOME || '/home/hatch/workspace/musecraft'
// `musecraft stop` owns this.
const hidden = ['mc.endSession']
const aliases = {screenshot:'screen.capture', capture:'screen.capture'}

function send(payload) {
  return new Promise((resolve, reject) => {
    const req = http.request({socketPath:path.join(dataDir,'controller.sock'), path:'/', method:'POST',
      headers:{'Content-Type':'application/json'}}, res => {
      let body = ''
      res.on('data', c => { body += c })
      res.on('error', reject)
      res.on('end', () => { try { resolve(JSON.parse(body)) } catch { reject(new Error('controller returned invalid JSON')) } })
    })
    req.on('error', reject)
    if (payload.verb !== 'standby') req.setTimeout(70 * 60000, () => req.destroy(new Error('controller timed out')))
    req.end(JSON.stringify({action:'cli', ...payload}))
  })
}
// Only a request that never reached the controller is safe to resend.
async function request(payload) {
  const until = Date.now() + 20000
  for (;;) {
    try {
      const response = await send(payload)
      if (!response.ok) throw Object.assign(new Error(response.error?.message ?? 'controller error'), {final:true})
      return response.value
    } catch (e) {
      if (e.final || Date.now() >= until || !/ENOENT|ECONNREFUSED/.test(e.message)) throw e
      await new Promise(resolve => setTimeout(resolve, 500))
    }
  }
}
// key=value pairs; values are JSON when they parse, text otherwise. A single
// JSON object argument is accepted too.
function parse(args) {
  if (args.length === 1 && args[0].trim().startsWith('{')) return JSON.parse(args[0])
  const input = {}
  for (const arg of args) {
    const at = arg.indexOf('=')
    if (at < 1) throw new Error(`expected key=value, got "${arg}"`)
    const raw = arg.slice(at + 1)
    let value
    try { value = JSON.parse(raw) } catch { value = raw }
    input[arg.slice(0, at)] = value
  }
  return input
}
function resolve(name) {
  const method = aliases[name] ?? (name.includes('.') ? name : 'mc.' + name)
  if (!Object.hasOwn(tools, method) || hidden.includes(method)) return null
  return method
}
const short = method => method.replace(/^mc\./, '')
function help(name) {
  if (name) {
    const method = resolve(name)
    if (!method) throw new Error(`unknown action "${name}"; run \`musecraft help\``)
    const tool = tools[method]
    const params = tool.parameters.properties ?? {}
    const required = tool.parameters.required ?? []
    return [`musecraft ${short(method)} ${Object.keys(params).map(k => required.includes(k) ? `${k}=…` : `[${k}=…]`).join(' ')}`,
      tool.description, 'Parameters: ' + JSON.stringify(params)].join('\n')
  }
  const lines = ['Commands (values are JSON when they parse, e.g. pos=[1,64,2] count=8 item=minecraft:oak_log):',
    '  musecraft play [server=HOST:PORT player=NAME owner=NAME client=JSON]   join and resume play (remembers settings)',
    '  musecraft client                    run the game client; leave it running in the background',
    '  musecraft login [stop]              open Prism (no game) for the one-time Microsoft sign-in, in the background; stop closes it',
    '  musecraft accounts [keep NAME]      list signed-in accounts; keep signs out every other account and makes NAME the default',
    '  musecraft screen shot|click X Y|type TEXT|key NAME   see and drive the client display',
    '  musecraft state                     full current state and any new events',
    '  musecraft wait [seconds]            wait until something needs you (default 300)',
    '  musecraft standby                   keep running in the background; it exits to wake you',
    '  musecraft stop [reason=TEXT]        end play when asked to stop',
    '  musecraft help ACTION               one action\'s parameters',
    'Actions (start or replace your one running action, or read the world):']
  for (const [method, tool] of Object.entries(tools)) {
    if (hidden.includes(method)) continue
    const first = tool.description.split(/(?<=\.)\s/)[0]
    lines.push(`  ${short(method).padEnd(14)} ${first}`)
  }
  return lines.join('\n')
}
// The licensed client needs the managed exec's egress, so it lives in the
// player's own long-running command instead of a service. `login` runs only
// Prism's window, for the one-time Microsoft sign-in.
function runClient(config, login = false) {
  const client = config.client ?? (login ? {display:':95', headless:true} : null)
  if (!client) throw new Error('no client configured; run `musecraft play client=\'{"display":":95","headless":true}\'` once')
  if (clientRunning(dataDir)) return console.log(login ? 'The Minecraft client is running; stop it before signing in.' : 'The Minecraft client is already running.')
  const display = String(client.display ?? ':95')
  const args = [path.join(__dirname, 'client_lifetime.py'), '--display', display, ...(client.headless === false ? [] : ['--headless']), ...(login ? ['--login'] : [])]
  console.log(login
    ? `Prism is opening on ${display} for sign-in. Leave this command running; see it with \`musecraft screen shot\`, and it ends when Prism closes.`
    : 'Minecraft client running. Leave this command running; it ends only if the game exits.')
  const child = spawn(python(), args, {stdio:['ignore','inherit','inherit'], env:{...process.env, MUSECRAFT_HOME:dataDir}})
  for (const signal of ['SIGTERM','SIGINT']) process.on(signal, () => child.kill(signal))
  return new Promise(resolve => child.on('exit', code => {
    if (login) return resolve(console.log(accounts([])))
    console.log(code === 0 ? 'Minecraft client stopped.' : 'Minecraft client exited. Run `musecraft client` again to restart it.')
    process.exitCode = code ?? 1
    resolve()
  }))
}
// Prism keeps signed-in accounts in accounts.json and rewrites it when it
// exits, so `keep` refuses while Prism is open.
function accounts(rest) {
  let file, data
  try {
    file = path.join(JSON.parse(fs.readFileSync(path.join(dataDir, 'setup-result.json'), 'utf8')).prismData, 'accounts.json')
    data = JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (e) { if (e.code === 'ENOENT') return 'No account is signed in. Run `musecraft login`.'; throw e }
  const names = data.accounts.map(a => (a.profile?.name ?? 'an account without Minecraft') + (a.active ? ' (default)' : ''))
  if (rest[0] !== 'keep')
    return names.length ? `Signed in: ${names.join(', ')}. The default account plays.` : 'No account is signed in. Run `musecraft login`.'
  if (!rest[1]) throw new Error('usage: musecraft accounts keep NAME')
  let prismOpen = clientRunning(dataDir)
  try { process.kill(Number(fs.readFileSync(path.join(dataDir, 'client-login.pid'), 'utf8')), 0); prismOpen = true } catch {}
  if (prismOpen) throw new Error('Prism is open; run `musecraft login stop` (and stop the client) first')
  const keep = data.accounts.find(a => a.profile?.name?.toLowerCase() === rest[1].toLowerCase())
  if (!keep) throw new Error(`no signed-in account is named ${rest[1]}; signed in: ${names.join(', ') || 'none'}`)
  data.accounts = [{...keep, active:true}]
  fs.writeFileSync(file + '.tmp', JSON.stringify(data, null, 4) + '\n', {mode:0o600})
  fs.renameSync(file + '.tmp', file)
  return `${keep.profile.name} is now the only signed-in account and plays by default.`
}
const python = () => fs.existsSync(path.join(dataDir, 'venv/bin/python')) ? path.join(dataDir, 'venv/bin/python') : 'python3'
function readConfig() {
  try { return JSON.parse(fs.readFileSync(path.join(dataDir, 'cli-config.json'), 'utf8')) }
  catch (e) { if (e.code === 'ENOENT') return {}; throw e }
}
module.exports = async argv => {
  const [verb, ...rest] = argv
  if (verb === 'help' || verb === '--help') return console.log(help(rest[0]))
  if (verb === 'standby') {
    // Outlive controller restarts: exiting is what wakes the player.
    for (;;) {
      try { return console.log((await request({verb})).text) }
      catch (e) {
        if (e.final) throw e
        await new Promise(resolve => setTimeout(resolve, 2000))
        spawnSync(python(), [path.join(__dirname, 'start.py'), '--quiet'], {stdio:'ignore'})
      }
    }
  }
  let result
  if (verb === 'client') return runClient(readConfig())
  if (verb === 'login' && rest[0] === 'stop') {
    try { process.kill(Number(fs.readFileSync(path.join(dataDir, 'client-login.pid'), 'utf8')), 'SIGTERM') }
    catch { return console.log('Prism sign-in is not running.') }
    return console.log('Closing Prism. Start the game with `musecraft client` once the sign-in command has ended.')
  }
  if (verb === 'login') return runClient(readConfig(), true)
  if (verb === 'accounts') return console.log(accounts(rest))
  if (verb === 'screen') {
    const display = String(readConfig().client?.display ?? ':95')
    const run = spawnSync(python(), [path.join(__dirname, 'screen.py'), '--display', display, ...rest], {stdio:'inherit', env:{...process.env, MUSECRAFT_HOME:dataDir}})
    process.exitCode = run.status ?? 1
    return
  }
  if (verb === 'play') {
    const config = {...readConfig(), ...parse(rest)}
    if (!config.server || !config.player || !config.owner)
      throw new Error('first run needs server=HOST:PORT player=NAME owner=NAME (owner: the Minecraft name of the person you play with)')
    fs.writeFileSync(path.join(dataDir, 'cli-config.json'), JSON.stringify(config, null, 2) + '\n', {mode:0o600})
    result = await request({verb, input:{server:config.server, player:config.player, owner:config.owner}})
  } else if (verb === 'state') result = await request({verb})
  else if (verb === 'wait') result = await request({verb, input:{seconds:rest[0] ? Number(rest[0]) : undefined}})
  else if (verb === 'stop') result = await request({verb, input:parse(rest)})
  else {
    const method = resolve(verb ?? '')
    if (!method) throw new Error(`unknown command "${verb ?? ''}"; run \`musecraft help\``)
    const input = parse(rest)
    try { validate(tools[method].parameters, input) }
    catch (e) { throw new Error(`${e.message} for ${short(method)}\n${help(verb)}`) }
    result = await request({verb:'act', method, input})
  }
  console.log(result.text)
}
