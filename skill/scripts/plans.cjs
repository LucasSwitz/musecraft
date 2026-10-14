const fs = require('node:fs')
const path = require('node:path')
const copy = value => JSON.parse(JSON.stringify(value))
const idPattern = /^[a-zA-Z0-9_-]{1,64}$/
const blockPattern = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/

function properties(value = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('state must be a property object')
  return Object.fromEntries(Object.entries(value).sort().map(([key, val]) => {
    if (!/^[a-z_]+$/.test(key) || !/^[a-z0-9_-]+$/.test(String(val))) throw new Error('Invalid block state')
    return [key, String(val)]
  }))
}
function normalize(blocks) {
  if (!Array.isArray(blocks) || !blocks.length || blocks.length > 4096) throw new Error('Use 1..4096 blocks')
  const seen = new Set()
  return blocks.map(item => {
    if (!Array.isArray(item.pos) || item.pos.length !== 3 || !item.pos.every(Number.isSafeInteger)) throw new Error('Block positions must be three integers')
    const key = item.pos.join(',')
    if (seen.has(key)) throw new Error('Duplicate build position')
    seen.add(key)
    if (!blockPattern.test(item.block)) throw new Error('Use namespaced block IDs')
    if (item.replace !== undefined && !blockPattern.test(item.replace)) throw new Error('replace must name the expected existing block')
    return { pos: [...item.pos], block: item.block, state: properties(item.state), ...(item.replace ? { replace: item.replace } : {}) }
  })
}
// Operational state changes during play do not change the built structure.
const transient = new Set(['open','powered','lit','occupied','triggered'])
function matches(block, wanted) {
  return block.name === wanted.block && Object.entries(wanted.state || {}).every(([k, v]) => transient.has(k) || String(block.properties?.[k]) === v)
}
module.exports = ({ dataDir }) => {
  const dir = path.join(dataDir, 'plans')
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
  const file = id => {
    if (!idPattern.test(id)) throw new Error('planId must be 1..64 letters, digits, underscores or hyphens')
    return path.join(dir, id + '.json')
  }
  const save = plan => {
    const dest = file(plan.id)
    fs.writeFileSync(dest + '.tmp', JSON.stringify(plan), { mode: 0o600 })
    fs.renameSync(dest + '.tmp', dest)
  }
  const get = id => JSON.parse(fs.readFileSync(file(id), 'utf8'))
  return {
    get,
    register(id, blocks, options = {}) {
      const normalized = normalize(blocks)
      if (fs.existsSync(file(id))) {
        const existing = get(id)
        if (JSON.stringify(existing.blocks) !== JSON.stringify(normalized)) throw new Error('plan_id_conflict: use a new planId for a changed design')
        return existing
      }
      const plan = { id, blocks: normalized, world: null, options }
      save(plan)
      return plan
    },
    bind(id, doc) {
      if (!doc.server || !doc.dimension) throw new Error('world_identity_unavailable')
      const world = { server: doc.server, dimension: doc.dimension }, plan = get(id)
      if (plan.world && JSON.stringify(plan.world) !== JSON.stringify(world)) throw new Error('plan_world_mismatch')
      if (!plan.world) { plan.world = world; save(plan) }
      return copy(plan)
    },
  }
}
module.exports.normalize = normalize
module.exports.matches = matches
