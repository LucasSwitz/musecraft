const {distance}=require('./navigation.cjs')

module.exports=async({args:a,io,nav,progress})=>{
  let view=await io.check()
  if(view.screen)await io.write('closeScreen')
  if(view.vehicle&&view.vehicle.id!==a.entityId)throw io.fail('blocked','dismount_current_vehicle_first')
  const first=view.vehicle?.id===a.entityId?view.vehicle:view.entities.find(e=>e.id===a.entityId)
  if(!first)throw io.fail('blocked','target_gone')
  if(first.mount?.baby)throw io.fail('blocked','mount_requires_adult')
  if(first.mount?.steerable===false)throw io.fail('blocked','mount_not_steerable')
  if(first.mount&&(!first.mount.tame||!first.mount.saddled)) {
    if(a.prepare===false)throw io.fail('blocked','mount_requires_tamed_saddled_horse')
    if(!view.inventory.some(s=>s.slot<36&&s.name==='minecraft:saddle'))throw io.fail('blocked','missing_item:minecraft:saddle')
  }
  let closest=Infinity,progressAt=Date.now(),attempts=0,stage='approaching'
  for(;;) {
    view=await io.check()
    const riding=view.vehicle?.id===a.entityId
    const e=riding?view.vehicle:view.entities.find(e=>e.id===a.entityId)
    if(!e?.alive)throw io.fail('blocked','target_gone')
    if(e.uuid!==first.uuid)throw io.fail('cancelled','entity_identity_changed')
    if(view.vehicle&&!riding)throw io.fail('blocked','vehicle_changed')
    if(riding&&(!e.mount||(e.mount.tame&&e.mount.saddled)))
      return {vehicle:e,confirmation:'riding_observed',attempts,
        tamed:!!e.mount&&!first.mount.tame,saddled:!!e.mount&&!first.mount.saddled}
    if(riding) {
      stage=e.mount.tame?'saddling':'taming'
      progress({stage,attempts,entityId:e.id})
      if(e.mount.tame) {
        await io.write('dismount')
        const until=Date.now()+5000
        while((await io.check()).vehicle) {
          if(Date.now()>until)throw io.fail('blocked','dismount_not_confirmed')
          await io.pause(100)
        }
        // Clear the dismount input before boarding again after saddle confirmation.
        await io.write('moveStop')
      } else await io.pause(250)
      closest=Infinity;progressAt=Date.now()
      continue
    }
    const gap=distance(view.pos,e.pos)
    if(gap<closest-.2){closest=gap;progressAt=Date.now()}
    if(Date.now()-progressAt>15000)throw io.fail('blocked','mount_approach_no_progress')
    try {
      const result=await io.write('mountEntity',{entityId:e.id,uuid:e.uuid,prepare:a.prepare!==false})
      if(!result.accepted)throw io.fail('blocked','mount_interaction_rejected')
      stage=result.stage;attempts++;progress({stage,attempts,entityId:e.id})
      // The next attempt follows an observed server passenger or saddle update.
      await io.pause(500)
    } catch(error) {
      if(!/entity_out_of_reach|entity_occluded/.test(error.message))throw error
      stage='approaching'
      await nav.approach(e.pos,1.5,{mode:'walk',oneStep:true,entityId:e.id,uuid:e.uuid})
    }
  }
}
