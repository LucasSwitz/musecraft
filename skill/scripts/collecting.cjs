// Sweep one fixed area. Native discovery owns entity identity and stack capacity;
// the controller owns routing, cancellation, recovery and inventory confirmation.
const {distance}=require('./navigation.cjs')
const volume=require('./block-volume.cjs')
const totals=doc=>{
  const counts={}
  for(const s of doc.inventory)counts[s.name]=(counts[s.name]??0)+s.count
  return counts
}
module.exports=async({args,io,nav,progress,confirm})=>{
  const before=await io.check(),initial=totals(before)
  const area={near:volume.block(args.near??before.pos),radius:args.radius??16}
  const attempts=require('./pickup-attempts.cjs')(io),rejected=new Map(),failures=[]
  const gains=doc=>Object.entries(totals(doc)).map(([item,count])=>({item,count:Math.max(0,count-(initial[item]??0))})).filter(s=>s.count>0)
  const clearedBefore=[...(io.pickupClearance?.values()??[])].reduce((n,s)=>n+s.size,0)
  const cleared=()=>[...(io.pickupClearance?.values()??[])].reduce((n,s)=>n+s.size,0)-clearedBefore
  const report=(doc,drops,patch={})=>{
    const collected=gains(doc),gained=collected.reduce((n,s)=>n+s.count,0)
    progress({completed:Array.from({length:gained},(_,i)=>i),remaining:drops.length,
      collected,area,cleared:cleared(),...patch})
    return collected
  }
  const scan=async()=> (await io.read('droppedItems',area)).filter(e=>volume.contains(area,e.pos))
  const finish=(doc,drops)=>{
    const remaining=drops.map(d=>({id:d.id,uuid:d.uuid,item:d.item.name,pos:d.pos,
      ...(d.pickupCapacity===0?{reason:'inventory_full'}:rejected.get(d.uuid)??{reason:'pickup_not_confirmed'})}))
    const collected=report(doc,drops),complete=!remaining.length
    const reason=complete?'area_clear':remaining.every(d=>d.reason==='inventory_full')?'inventory_full':'drops_unreachable'
    return {status:complete?'completed':collected.length?'partial':'blocked',reason,complete,area,collected,
      remaining:remaining.slice(0,64),remainingCount:remaining.length,omitted:Math.max(0,remaining.length-64),
      blocker:remaining.find(d=>d.blocker)?.blocker??null,cleared:cleared(),failures:failures.slice(-8),
      explanation:complete?'No dropped items remain in the loaded search area.':reason==='inventory_full'
        ?'Remaining drops do not fit in inventory. Free space, then collect this area again.'
        :'Reachable drops were collected. Inspect the remaining items and their access blockers.',
      confirmation:'inventory_changes_and_remaining_entities'}
  }
  for(;;) {
    const doc=await io.check(),drops=await scan()
    report(doc,drops,{phase:'collecting'})
    if(!drops.length)return finish(await io.check(),drops)
    const candidates=[]
    for(const drop of drops) {
      if(drop.pickupCapacity===0||rejected.has(drop.uuid))continue
      const previous=await attempts.failure(drop,doc.pos)
      if(previous)rejected.set(drop.uuid,previous)
      else candidates.push(drop)
    }
    candidates.sort((a,b)=>distance(a.pos,doc.pos)-distance(b.pos,doc.pos)||a.id-b.id)
    if(!candidates.length)return finish(doc,drops)
    const next=candidates[0],prior=totals(doc)[next.item.name]??0
    const gainedTarget=async()=>((totals(await io.check())[next.item.name]??0)>prior)
    let failure
    progress({target:next.pos})
    try {
      await require('./pickup-access.cjs')({drop:next,area,io,nav,confirm,progress,held:gainedTarget})
      const until=Date.now()+800
      while(Date.now()<until&&!await gainedTarget()) {
        if(!(await scan()).some(d=>d.uuid===next.uuid))break
        await io.pause(100)
      }
    }catch(e) {
      if(e.status==='cancelled'||e.status==='uncertain')throw e
      if(!e.blocker&&!/no_route_in_region|search_incomplete|movement_stalled|no_acceptable_destination|target_gone|requires_elevation|descent_unreachable/.test(e.message))throw e
      failure={reason:e.blocker?.reason??e.message,
        blocker:{...e.blocker,target:next.pos,entityId:next.id,cause:e.blocker?.cause??e.message,position:(await io.check()).pos}}
    }
    const after=await io.check(),remaining=await scan(),current=remaining.find(d=>d.uuid===next.uuid)
    // A merged, despawned or externally picked-up entity is rediscovered by the
    // next scan. Disappearance itself never counts as an inventory gain.
    if(current&&(totals(after)[next.item.name]??0)<=prior&&current.pickupCapacity!==0) {
      failure??={reason:'pickup_not_confirmed',blocker:{target:current.pos,entityId:current.id,
        explanation:'The approach ended but this drop remains and inventory did not increase.'}}
      failures.push(failure);rejected.set(current.uuid,failure)
      await attempts.reject(current,after.pos,failure)
    }
  }
}
