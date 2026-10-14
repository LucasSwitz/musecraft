#!/usr/bin/env node
// `musecraft observe`, `musecraft session status|retry` and `musecraft avatar
// status|load PATH|upload|reload` are operator commands that print the
// controller's JSON; everything else is play.
const http = require('node:http')
const path = require('node:path')
const [action, operation, target] = process.argv.slice(2)
if (!['observe', 'session', 'avatar'].includes(action)) {
  require('./cli.cjs')(process.argv.slice(2)).catch(e => { console.error('musecraft: ' + e.message); process.exitCode = 1 })
  return
}
const usage = action === 'session' ? !['status', 'retry'].includes(operation)
  : action === 'avatar' ? !['status', 'load', 'upload', 'reload'].includes(operation ?? 'status') || (operation === 'load' && !target) : false
if (usage) {
  console.error('Usage: musecraft observe | musecraft session status|retry | musecraft avatar status|load PATH|upload|reload')
  process.exitCode = 2
  return
}
const dataDir = process.env.MUSECRAFT_HOME || '/home/hatch/workspace/musecraft'
function request(payload) {
  return new Promise((resolve, reject) => {
    const req = http.request({socketPath:path.join(dataDir, 'controller.sock'), path:'/', method:'POST',
      headers:{'Content-Type':'application/json'}}, res => {
      let body = ''
      res.on('data', c => { body += c })
      res.on('error', reject)
      res.on('end', () => { try { resolve(JSON.parse(body)) } catch { reject(new Error('Controller returned invalid JSON')) } })
    })
    req.on('error', reject)
    req.setTimeout(action === 'avatar' ? 60000 : 10000, () => req.destroy(new Error('Request timed out')))
    req.end(JSON.stringify(payload))
  })
}
request({action, operation, ...(action === 'avatar' && operation === 'load' ? {path:path.resolve(target)} : {})})
  .then(response => { console.log(JSON.stringify(response)); if (!response.ok) process.exitCode = 1 })
  .catch(e => { console.error('Musecraft controller: ' + e.message); process.exitCode = 1 })
