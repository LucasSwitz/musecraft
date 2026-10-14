const distance = (a,b) => Math.hypot(...a.map((v,i)=>v-b[i]))
const xyz = p => ({x:p[0],y:p[1],z:p[2]})
module.exports = ({io,progress}) => {
  let continuing=null
  async function walk(target,options={}) {
    const doc=await io.check(),flying=!doc.vehicle&&(options.mode==='fly'||(options.mode!=='walk'&&doc.canFly))
    const destination=options.entityId===undefined?{...xyz(target),...(options.distance===undefined?{}:{distance:options.distance})}:{entityId:options.entityId,distance:options.distance??2.5}
    const request={...destination,flying,tolerance:options.tolerance??.25,goal:options.goal??'exact',...(options.returnable?{returnable:true}:{}),...(options.clearVegetation?{clearVegetation:true,protectedBlocks:options.protectedBlocks??[]}:{}),...(options.action?{action:options.action}:{}),...(options.maxPathCost===undefined?{}:{maxPathCost:options.maxPathCost})}
    if(options.area)request.area=options.area
    if(options.protectedBlocks)request.protectedBlocks=options.protectedBlocks
    if(options.descend)request.descend=true
    for(const key of ['allowPlace','maxPlace','placeBlock'])if(options[key]!==undefined)request[key]=options[key]
    if(options.allowBreak)request.allowBreak=true
    if(options.use_hands)request.use_hands=true
    const key=JSON.stringify(request)
    const cached=options.oneStep&&continuing?.key===key?await io.write('movePoll',{id:continuing.movement.id}):null
    const movement=cached?.status==='running'?cached:await io.write('moveStart',request)
    continuing=options.oneStep?{key,movement}:null
    const started=Date.now()
    while(true) {
      const state=await io.write('movePoll',{id:movement.id})
      const currentTarget=state.target??target
      progress({target:currentTarget,distance:currentTarget?distance(state.pos,currentTarget):null,movement:state})
      if(state.status==='completed'){continuing=null;return state}
      if(state.status!=='running'){continuing=null;throw Object.assign(io.fail(state.status,state.reason),{navigation:state,...(state.blocker?{blocker:state.blocker}:{})})}
      if(options.oneStep&&Date.now()-started>=500)return state
      await io.pause(150)
    }
  }
  async function go(target,options={}) {
    return walk(target,options)
  }
  async function approach(target,range,options={}) {
    return go(target,{...options,...(options.entityId===undefined?{}:{distance:range}),goal:'action',action:{...options.action,range,...(options.entityId===undefined?{}:{entityId:options.entityId,uuid:options.uuid})}})
  }

  return {go,approach}
}
module.exports.distance=distance
module.exports.xyz=xyz
