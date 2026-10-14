module.exports=async({r,io,nav,progress,confirm})=>{
  const a=r.args
  if(r.kind==='flee') {
    if((await io.check()).screen)await io.write('closeScreen')
    const started=await io.write('moveStart',{flee:true,...a})
    r.fleeReady.resolve(started.id)
    let revision=-1
    while(true) {
      await io.check()
      const state=await io.write('movePoll',{id:started.id})
      // Persist meaningful phases, not every changing distance sample.
      if(state.phaseRevision!==revision) {
        revision=state.phaseRevision
        progress({entityId:state.entityId,uuid:state.uuid,phase:state.phase,targets:state.targets,
          escape:{revision,distance:state.distance,safeDistance:state.safeDistance}})
      }
      if(state.status==='completed')return state
      if(state.status!=='running')throw Object.assign(io.fail(state.status,state.reason),{navigation:state})
      await io.pause(150)
    }
  }
  if(r.kind==='wake') {
    if(!(await io.check()).sleeping)return {confirmation:'awake_observed'}
    await io.write('wake')
    const until=Date.now()+5000
    while(Date.now()<until) {
      if(!(await io.check()).sleeping)return {confirmation:'awake_observed'}
      await io.pause(100)
    }
    throw io.fail('blocked','wake_not_confirmed')
  }
  if(r.kind==='mount')return require('./mounting.cjs')({args:a,io,nav,progress})
  if(r.kind==='dismount') {
    await io.write('dismount')
    const until=Date.now()+5000
    while(Date.now()<until) {
      const doc=await io.check()
      if(!doc.vehicle)return {vehicle:null,confirmation:'dismounted_observed'}
      await io.pause(100)
    }
    throw io.fail('blocked','vehicle_change_not_confirmed')
  }
  if(r.kind==='pillar') {
    const doc=await io.check(),target=[Math.floor(doc.pos[0])+.5,Math.floor(doc.pos[1])+a.height,Math.floor(doc.pos[2])+.5]
    return nav.go(target,{goal:'near',distance:.3,mode:'walk',allowPlace:true,maxPlace:a.height,placeBlock:a.block})
  }
  if(r.kind==='collect')return require('./collecting.cjs')({args:a,io,nav,progress,confirm})
  throw new Error('Unknown mobility action')
}
