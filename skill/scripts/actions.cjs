// Player inventory slots for worn items: boots 36, leggings 37, chestplate 38, helmet 39, offhand 40.
const wearSlot = item => {
  const id = item.slice(item.indexOf(':') + 1)
  if (/(^|_)helmet$/.test(id)) return 39
  if (/_chestplate$/.test(id) || id === 'elytra') return 38
  if (/_leggings$/.test(id)) return 37
  if (/_boots$/.test(id)) return 36
  if (id === 'shield') return 40
}
module.exports = ({ perform, observe, rpc }) => {
  const distance = (a, b) => Math.hypot(...a.map((v, i) => v - b[i]))
  const mc = {
    observe,
    animations: () => rpc('animations'),
    animate: (name, value = 1) => {
      if (typeof name !== 'string' || !name.length || name.length > 128) throw new Error('Animation name must be 1..128 characters')
      if (!Number.isInteger(value) || value < 0 || value > 255) throw new Error('Animation value must be an integer 0..255')
      return perform('animate', async io => io.write('animate', { name, value }))
    },
    equip: item => {
      if(typeof item !== 'string' || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(item))throw new Error('Use a namespaced item ID')
      const worn=wearSlot(item)
      return perform('equip', async io => {
        // Armor, elytra and shields belong in their equipment slot, not the hand.
        if(worn!==undefined) {
          const inv=require('./inventory-operations.cjs')(io)
          const s=await inv.state(), to=s.slots.find(x=>x.inventorySlot===worn)
          if(!to)throw io.fail('blocked','equipment_slot_not_in_current_screen; close the container first')
          if(to.name===item)return {worn:item,inventorySlot:worn,confirmation:'already_worn'}
          const from=s.slots.find(x=>x.role==='inventory'&&x.name===item)
          if(!from)throw io.fail('blocked',`missing_item:${item}`)
          const after=await inv.submit('inventorySwap',s,{from:from.id,to:to.id})
          if(after.slots.find(x=>x.inventorySlot===worn)?.name!==item)throw io.fail('blocked','equip_not_confirmed')
          return {worn:item,inventorySlot:worn,confirmation:'server_inventory_update'}
        }
        const selected=await io.write('selectItem',{item})
        await io.pause(150)
        const current=await io.check()
        if(current.selectedItem!==item)throw io.fail('blocked','equip_not_confirmed')
        return {...selected,confirmation:'selected_item_observed'}
      })
    },
    inventory: () => rpc('inventory'),
    entities: async () => { const d=await rpc('observe'); return {connected:d.connected,entities:d.entities||[],scope:d.entityScope} },
    container: () => rpc('container').then(require('./inventory-operations.cjs').view),
    recipes: item => rpc('recipes',{item}),
    blockAt: (x, y, z) => rpc('blockAt', { x, y, z }),
    findBlocks: (name, radius = 8) => typeof name==='object' ? rpc('findBlocksBatch',name) : rpc('findBlocks', { name, radius }),
    inspectRegion: options => rpc('inspectRegion', options),
    protectArea: request => perform('protectArea', io => io.write('protectArea',request)),
    scanBlocks: (options = {}) => rpc('scanBlocks', options),
    chat: text => perform('chat', async io => { await io.write('chat', { text }); return { submitted: true } }),

  }
  return {mc}
}
