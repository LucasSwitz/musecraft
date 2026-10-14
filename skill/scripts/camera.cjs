const {xyz,distance}=require('./navigation.cjs')
module.exports=async({io,nav,progress,plan,options})=>{
  if(options.view&&options.view!=='exterior')throw io.fail('blocked','supported_view_is_exterior')
  const floor=Math.min(...plan.blocks.map(b=>b.pos[1]))
  // Frame the above-ground building, without including a long garden path.
  const elevated=plan.blocks.filter(b=>b.pos[1]>floor+1)
  const body=elevated.length?elevated:plan.blocks
  const min=[0,1,2].map(i=>i===1?floor:Math.min(...body.map(b=>b.pos[i])))
  const max=[0,1,2].map(i=>Math.max(...body.map(b=>b.pos[i]))+1)
  const center=min.map((v,i)=>(v+max[i])/2),info=await io.read('cameraInfo')
  const span=Math.hypot(max[0]-min[0],max[2]-min[2],max[1]-min[1])
  const radius=Math.max(7,span/(2*Math.sin(info.fov*Math.PI/360)))*1.1
  const points=[center,...[-1,1].flatMap(x=>[-1,1].flatMap(y=>[-1,1].map(z=>center.map((v,i)=>v+[x,y,z][i]*(max[i]-min[i])*.45))))]
  const current=await io.check(),candidates=[]
  for(const scale of [1,1.3])for(let angle=0;angle<8;angle++) {
    const a=(angle+.5)*Math.PI/4
    const target=[center[0]+Math.cos(a)*radius*scale,options.mode==='fly'?center[1]+radius*.22:current.pos[1],center[2]+Math.sin(a)*radius*scale]
    const eye=[target[0],target[1]+1.62,target[2]]
    const view=await io.read('viewBounds',{min,max,points,eye,flying:options.mode==='fly'})
    if(view.valid&&view.visible===points.length)candidates.push(target)
  }
  while(candidates.length) {
    const here=(await io.check()).pos
    candidates.sort((a,b)=>distance(a,here)-distance(b,here))
    const target=candidates.shift()
    try{await nav.go(target,{...options,tolerance:.3})}
    catch(e){if(/no_safe_route|navigation_no_progress|no_route_in_region|search_incomplete|movement_stalled|no_acceptable_destination|terrain_unloaded/.test(e.message))continue;throw e}
    const view=await io.read('viewBounds',{min,max,points,flying:options.mode==='fly'})
    progress({target,visible:view.visible,total:points.length})
    if(view.visible!==points.length)continue
    await io.write('lookAt',xyz(center))
    try {
      if(options.hideHud!==false)await io.write('setHudHidden',{hidden:true})
      await io.pause(200)
      return {...await io.read('screenshot'),planId:plan.id,bounds:{min,max},view:'exterior'}
    } finally {await io.restoreHud(info.hudHidden)}
  }
  throw io.fail('blocked','no_clear_exterior_view')
}
