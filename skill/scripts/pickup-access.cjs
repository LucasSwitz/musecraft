const {xyz}=require('./navigation.cjs')
const routeFailure=e=>/^(no_route_in_region|movement_stalled|no_acceptable_destination)$/.test(e.message)
const explanations={
  pickup_clearance_stalled:'A previously removed obstruction reappeared. Choose another approach or inspect the changing terrain.',
  pickup_clearance_limit:'Local pickup clearance removed eight blocks without completing access. Choose another approach.',
  pickup_clearance_out_of_reach:'The local pickup approach is outside safe clearing reach. Move closer or choose another approach.',
  pickup_route_unresolved:'A local corridor appears clear but navigation failed. No obstruction was removed for this attempt.',
  no_safe_pickup_access:'No supported local pickup corridor could be established. The failed search does not rule out other routes.',
  access_outside_gather_area:'Pickup access would require clearing outside the requested area.',
  unbreakable_obstruction:'An unbreakable block obstructs the local pickup approach.',
  container_contents_unknown:'The local pickup approach would require breaking a container with unknown contents.',
  fluid_obstruction:'The local pickup approach contains or borders liquid.',
  unsafe_clearance:'Clearing this approach would remove unsafe support or release falling blocks.',
  missing_harvest_tool:'Inventory lacks a suitable tool for clearing the pickup approach.',
  pickup_landing_unconfirmed:'The player did not settle on the expected landing after clearing support.',
}

// Same native tool selection, digging and server acknowledgement as mining.
// Recompute real body obstructions after every change; never repeat a blind dig.
module.exports=async({drop,area,io,nav,confirm,progress,held})=>{
  const history=io.pickupClearance??=new Map()
  const cleared=history.get(drop.uuid)??new Set();history.set(drop.uuid,cleared)
  for(;;) {
    try{return await nav.go(drop.pos,{mode:'walk',goal:'action',
      action:{entityId:drop.id,uuid:drop.uuid,pickup:true,range:2.5},tolerance:.15,area})}
    catch(error) {
      if(!routeFailure(error))throw error
      const access=await io.read('pickupAccess',{entityId:drop.id,uuid:drop.uuid,area})
      const reason=cleared.size>=8?'pickup_clearance_limit':access.found&&cleared.has(access.target.join(','))?'pickup_clearance_stalled':access.reason
      const blocked=e=>Object.assign(e,{blocker:{...error.blocker,...access,
        target:drop.pos,entityId:drop.id,cause:error.message,reason:e===error?reason:e.message,
        explanation:explanations[e===error?reason:e.message]??'Pickup access recovery failed; inspect the reported obstruction and cause.',
        cleared:[...cleared].map(p=>p.split(',').map(Number))}})
      if(!access.found||cleared.size>=8||cleared.has(access.target.join(',')))throw blocked(error)
      progress({phase:'clearing_pickup_access',pickupAccess:access,cleared:cleared.size})
      try {
        await io.check()
        // Re-read immediately before mutation; expected block identity also
        // fences dig admission if another player changes the selected cell.
        const fresh=await io.read('pickupAccess',{entityId:drop.id,uuid:drop.uuid,area})
        if(!fresh.found||fresh.target.join(',')!==access.target.join(','))continue
        await io.write('selectTool',xyz(fresh.target))
        await confirm(io,await io.write('digAt',{...xyz(fresh.target),expected:fresh.block}),'minecraft:air')
        cleared.add(fresh.target.join(','))
        progress({phase:'collecting',cleared:cleared.size,pickupAccess:fresh})
        // Removing safe support can pick up the item while the player falls.
        // Let physics settle before asking for another standing route.
        const until=Date.now()+5000
        while(!(await io.check()).onGround) {
          if(Date.now()>=until)throw io.fail('blocked','pickup_landing_unconfirmed')
          await io.pause(75)
        }
        if(await held())return
      }catch(e) {
        if(e.status==='cancelled'||e.status==='uncertain'||/client_|session_|generation_|identity_/.test(e.message))throw e
        throw blocked(e)
      }
    }
  }
}
