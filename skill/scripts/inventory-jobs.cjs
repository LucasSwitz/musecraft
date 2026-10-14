module.exports = async ({r,io,nav,progress,options,reachable,materialFor}) => {
  const inv=require('./inventory-operations.cjs')(io), a=r.args
  async function open(target,expected) {
    if(!target)return inv.state()
    const block=await io.read('blockAt',{x:target[0],y:target[1],z:target[2]})
    if(!block.canOpenContainer)throw io.fail('blocked',`container_target_unavailable:${block.name}; inspect the current world`)
    if(expected&&!expected.includes(block.name))throw io.fail('blocked',`furnace_required:${block.name}`)
    let doc=await io.check()
    if(doc.screen)await io.write('closeScreen')
    await reachable(io,nav,target,options,()=>io.write('interactTarget',{x:target[0],y:target[1],z:target[2]}))
    const until=Date.now()+5000
    while(Date.now()<until) {
      doc=await io.check()
      if(doc.container)return inv.state()
      await io.pause(100)
    }
    throw io.fail('blocked','container_did_not_open')
  }
  const section = name => slot => slot.role===name
  if(r.kind==='openContainer')return {confirmation:'server_container_open',container:inv.view(await open(a.target))}
  if(r.kind==='drop')return inv.transfer(a.item,a.count,x=>inv.inventory(x)&&(a.slot===undefined||x.inventorySlot===a.slot),null,progress,true)
  if(r.kind==='moveItem')return inv.transfer(a.item,a.count,x=>x.inventorySlot!==undefined&&x.inventorySlot!==a.toSlot&&(a.fromSlot===undefined||x.inventorySlot===a.fromSlot),x=>x.inventorySlot===a.toSlot,progress)
  if(r.kind==='swapSlots') {
    const s=await inv.state(), from=s.slots.find(x=>x.inventorySlot===a.fromSlot),to=s.slots.find(x=>x.inventorySlot===a.toSlot)
    if(!from||!to)throw io.fail('blocked','inventory_slot_not_in_current_screen; close container first')
    return {confirmation:'server_inventory_update',container:inv.view(await inv.submit('inventorySwap',s,{from:from.id,to:to.id}))}
  }
  if(r.kind==='transfer') {
    const s=await open(a.target), area=a.section??'storage'
    if(!s.slots.some(section(area)) || s.type==='player')throw io.fail('blocked','requested_container_section_unavailable')
    if(a.direction==='deposit'&&area==='output')throw io.fail('blocked','cannot_deposit_into_output')
    return inv.transfer(a.item,a.count,a.direction==='deposit'?inv.inventory:section(area),a.direction==='deposit'?section(area):inv.inventory,progress)
  }
  if(r.kind==='craft')return require('./crafting.cjs')({a,io,inv,open,nav,options,materialFor,progress})
  if(r.kind==='smelt') {
    let s=await open(a.target,['minecraft:furnace','minecraft:smoker','minecraft:blast_furnace'])
    if(!s.furnace)throw io.fail('blocked','furnace_required')
    if(s.slots.some(x=>['input','output'].includes(x.role)&&x.count))throw io.fail('blocked','furnace_busy; inspect or withdraw existing input/output first')
    const recipe=await io.read('cookingRecipe',{item:a.item,fuel:a.fuel.item})
    if(!recipe.fuelTicks)throw io.fail('blocked','invalid_fuel')
    if(recipe.fuelTicks*a.fuel.count<recipe.cookTicks*a.count)throw io.fail('blocked','insufficient_fuel_for_batch')
    // Preflight both loads before changing the furnace.
    inv.plan(s,a.item,a.count,inv.inventory,section('input'))
    inv.plan(s,a.fuel.item,a.fuel.count,inv.inventory,section('fuel'))
    if(a.item===a.fuel.item && inv.count(s,a.item,inv.inventory)<a.count+a.fuel.count)throw io.fail('blocked','input_and_fuel_need_separate_items')
    const token=s.token
    await inv.transfer(a.item,a.count,inv.inventory,section('input'))
    await inv.transfer(a.fuel.item,a.fuel.count,inv.inventory,section('fuel'))
    if(a.wait===false)return {loaded:a.count,item:a.item,output:recipe.output.name,confirmation:'server_inventory_update',container:inv.view(await inv.state())}
    let lastOutput=0,lastProgress=Date.now()
    const expected=a.count*recipe.output.count
    while(true) {
      await io.check();s=await inv.state()
      if(s.token!==token)throw io.fail('cancelled','container_changed; furnace continues independently')
      const output=s.slots.find(x=>x.role==='output')
      const done=output.name===recipe.output.name?output.count:0
      progress({completed:Array.from({length:done},(_,i)=>i),remaining:expected-done})
      if(done>=expected)break
      if(done!==lastOutput) {lastOutput=done;lastProgress=Date.now()}
      if(Date.now()-lastProgress>recipe.cookTicks*50+10000)throw io.fail('blocked','furnace_stalled; inspect fuel and output')
      await io.pause(250)
    }
    await inv.transfer(recipe.output.name,expected,section('output'),inv.inventory)
    await io.write('closeScreen')
    return {item:recipe.output.name,count:expected,confirmation:'server_inventory_update'}
  }
  throw new Error('Unknown inventory action')
}
