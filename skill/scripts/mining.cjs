// One action owns route selection, permitted clearance, tool choice and confirmed removal.
const volume=require('./block-volume.cjs')
const reachChanged=/no_reachable_visible_face|mining_out_of_reach|mining_occluded/
const accessFailure=/local_excavation_cheaper|no_route_in_region|search_incomplete|movement_stalled|no_acceptable_destination|no_safe_mining_access|unsafe_support_removal|no_reachable_(visible_)?face/
module.exports=async({r,io,nav,progress,confirm,options})=>{
  const xyz=p=>({x:p[0],y:p[1],z:p[2]})
  const completed=new Set(r.progress.completed)
  let cleared=r.progress.cleared??0,removed=r.progress.removed??0,alreadyClear=r.progress.alreadyClear??0
  let adaptations=r.progress.adaptations??0
  for(let i=0;i<r.args.positions.length;i++) {
    if(completed.has(i))continue
    const target=r.args.positions[i],visits=new Set()
    let approaches=0,access,directAttempted=false,routeError,digTarget=null,recoveries=0
    const failedStances=new Set()
    const counts=()=>({completed:[...completed],remaining:r.args.positions.length-completed.size,
      removed,alreadyClear,cleared,adaptations,target,digTarget,approaches})
    const report=()=>progress(counts())
    const blocked=error=>{
      report()
      const cause=error.navigation?.reason??error.message
      const spatial=accessFailure.test(cause)||reachChanged.test(cause)
      const code=error.message.replace(/^java\.[^:]+:\s*/,'').split(':')[0]
      const support=spatial?access?.evidence?.supportRemoval:null
      const reason=support?.reason??(spatial&&access?.evidence?.aboveReach?'requires_elevation':error.blocker?.reason??
        (reachChanged.test(cause)?'mining_stance_changed':accessFailure.test(cause)?'sources_unreachable':code))
      const {supportRemoval,...evidence}=access?.evidence??{}
      const explanation=support?.explanation??error.blocker?.explanation??access?.explanation??
        (spatial?'Could not reach a safe standing position to mine the requested block. Inspect the reported local terrain, change the approach, then retry.':`Mining stopped: ${code}.`)
      const details={...error.blocker,...evidence,...support,target,digTarget,cause,approaches,
        completed:completed.size,removed,alreadyClear,cleared,remaining:r.args.positions.length-completed.size}
      if(reason==='requires_elevation') {
        details.suggestedTool='mc.pillar'
        details.explanation='The source is above mining reach and this approach failed. Choose a landing for mc.pillar or another source; another walking route may exist.'
      } else if(reason==='mining_stance_changed') {
        details.explanation='Reach or visibility changed while mining. The available alternate standing positions failed; move to stable footing or choose another source.'
      } else details.explanation=explanation
      // Put the useful failure first: later event/state summaries are bounded.
      return Object.assign(error,{blocker:{reason,explanation:details.explanation,target,digTarget,...support,...details,reason}})
    }

    const recover=async error=>{
      await io.stop()
      access=await io.read('miningAccess',xyz(target))
      if(access.done)return
      // Bound failed approaches, not the duration of useful mining. A fluid
      // current must not reset the same retry forever at slightly different Y.
      if(++recoveries>2)throw blocked(error)
      adaptations++
      const stances=await io.read('miningStances',xyz(digTarget??target))
      for(const stance of stances) {
        const key=JSON.stringify(stance)
        if(failedStances.has(key))continue
        failedStances.add(key);approaches++;report()
        try {
          await nav.go(stance,{...options,goal:'exact',tolerance:.15})
          directAttempted=false;visits.clear();report();return
        } catch(route) {
          if(!accessFailure.test(route.message))throw route
          routeError=route
        }
      }
      throw blocked(error)
    }
    report()
    try {
      while(true) {
        const doc=await io.check(),actual=await io.read('blockAt',xyz(target))
        if(actual.miningClear){alreadyClear++;break}
        if(r.args.expectedBlock&&actual.name!==r.args.expectedBlock)
          throw io.fail('blocked',`gather_target_changed:${target.join(',')}:${actual.name}`)
        access=await io.read('miningAccess',xyz(target))
        if(access.done){alreadyClear++;break}
        if(access.reason==='protected_structure')throw blocked(io.fail('blocked',access.reason))
        if(access.reason==='block_not_mineable')throw Object.assign(io.fail('blocked',access.reason),{blocker:{reason:access.reason}})
        if(access.reason==='mining_target_enclosed')throw blocked(io.fail('blocked',access.reason))
        if(access.approach) {
          const visit=JSON.stringify(['approach',doc.pos.map(v=>Math.round(v*4)),access.approach])
          if(visits.has(visit))throw blocked(io.fail('blocked','movement_stalled'))
          visits.add(visit);approaches++;report()
          try {await nav.go(access.approach,{...options,goal:'exact',tolerance:.15})}
          catch(error){throw blocked(error)}
          directAttempted=false
          continue
        }
        // Baritone chooses walking versus excavation in one native search.
        if(!directAttempted&&(access.obstruction||!access.reachable||access.reason)) {
          directAttempted=true;approaches++;report()
          try {
            await nav.go(target,{...options,goal:'action',action:{mining:true},tolerance:.15,area:r.args.area})
            continue
          }catch(error) {
            if(!accessFailure.test(error.message))throw error
            routeError=error;adaptations++
            access=await io.read('miningAccess',xyz(target))
            if(r.args.area&&access.evidence?.aboveReach)throw blocked(error)
          }
        }
        if(access.reason)throw blocked(routeError??io.fail('blocked',access.reason))
        const next=access.target
        digTarget=next;report()
        if(r.args.area&&!volume.contains(r.args.area,next))
          throw blocked(io.fail('blocked','access_outside_gather_area'))
        if(!access.reachable) {
          if(approaches>=2)throw blocked(routeError??io.fail('blocked','no_reachable_face'))
          approaches++;report()
          try {await nav.go(next,{...options,goal:'action',action:{mining:true},tolerance:.15,area:r.args.area})}
          catch(error){throw blocked(error)}
          continue
        }
        const visit=JSON.stringify([doc.pos.map(v=>Math.round(v*4)),next])
        if(visits.has(visit))throw blocked(io.fail('blocked','no_reachable_face'))
        visits.add(visit)
        try {
          await io.write('selectTool',{...xyz(next),use_hands:options.use_hands===true&&!r.args.item,
            ...(!access.obstruction&&r.args.item?{item:r.args.item}:{})})
        } catch(error) {
          if(!/missing_harvest_tool/.test(error.message)||r.args.item)throw error
          throw Object.assign(io.fail('blocked','missing_harvest_tool'),{blocker:{reason:'missing_harvest_tool',
            target:next,suggestedTool:'mc.breakBlocks',retry:{use_hands:true},
            explanation:'No suitable harvest tool is available. Equip one, or retry mc.breakBlocks with use_hands:true to break more slowly and forfeit drops that require a tool.'}})
        }
        try {
          const admission=await io.write('digAt',{...xyz(next),...(!access.obstruction&&r.args.expectedBlock?{expected:r.args.expectedBlock}:{})})
          await confirm(io,admission,'minecraft:air')
        } catch(error) {
          if(!reachChanged.test(error.message))throw error
          await recover(error);continue
        }
        approaches=0;directAttempted=false;routeError=null;visits.clear()
        recoveries=0;failedStances.clear()
        if(access.obstruction)cleared++
        else {removed++;break}
        report()
      }
      completed.add(i);report()
    } catch(error) {
      if(error.status==='cancelled')throw error
      // Refresh the physical evidence after movement; keep the requested target
      // separate from a clearance block or a navigation standing position.
      try {access=await io.read('miningAccess',xyz(target))} catch { /* Preserve the original failure. */ }
      throw blocked(error)
    }
  }
  const changed=removed+cleared>0
  return {reason:changed?'blocks_removed':'already_clear',changed,
    explanation:changed
      ? `Removed ${removed} requested blocks and ${cleared} access obstructions; ${alreadyClear} requested cells were already clear.`
      : `Removed 0 blocks; all ${alreadyClear} requested cells were already clear. Inspect solid blocks before choosing another mining target.`,
    count:completed.size,removed,alreadyClear,cleared,adaptations,
    confirmation:removed||cleared?(alreadyClear?'mixed':'server_block_removals'):'already_clear'}
}
