// A deterministic player view. Runtime identity stays separate from game prose.
const clean = value => Object.fromEntries(Object.entries(value).filter(([,v])=>v!==undefined && v!==null))
const text = value => JSON.stringify(bounded(String(value)))
const short = value => String(value ?? 'unknown').replace(/^minecraft:/,'')
const number = value => Number.isFinite(value) ? Math.round(value*100)/100 : null
const position = value => Array.isArray(value) ? value.map(number) : null
const at = value => value ? '['+value.join(', ')+']' : 'unknown'
function bounded(value, depth=0) {
  if(value===undefined || value===null)return null
  if(typeof value==='string')return value.length>360?value.slice(0,360)+' [truncated]':value
  if(typeof value!=='object')return value
  if(depth>=4)return {omitted:true}
  if(Array.isArray(value))return value.slice(0,8).map(v=>bounded(v,depth+1)).concat(value.length>8?[{omitted:value.length-8}]:[])
  const entries=Object.entries(value).filter(([,v])=>v!==undefined && v!==null)
  return {...Object.fromEntries(entries.slice(0,16).map(([k,v])=>[k,bounded(v,depth+1)])),
    ...(entries.length>16?{omittedFields:entries.length-16}:{})}
}
function action(value) {
  return value ? clean({name:value.name,status:value.status,reason:bounded(value.reason),
    progress:bounded(value.progress),result:bounded(value.result)}) : null
}
function block(value) {
  return value ? clean({name:value.name,pos:value.pos,properties:value.properties,count:value.count,context:value.context}) : null
}
function items(rows, limit=24) {
  const occupied=(rows||[]).filter(r=>r.count>0)
  return {items:occupied.slice(0,limit).map(r=>clean({name:r.name,count:r.count,slot:r.slot,
    id:r.id,role:r.role,damage:r.damage||undefined,maxDamage:r.maxDamage||undefined})),
    omitted:Math.max(0,occupied.length-limit)}
}
function context(world, events=[], generatedAt=world.observedAt) {
  const observed=Date.parse(world.observedAt), generated=Date.parse(generatedAt)
  const ageMs=Number.isFinite(observed)&&Number.isFinite(generated)?Math.max(0,generated-observed):null
  const player=world.player||{}, origin=player.pos||[]
  const threats=world.visibleHostiles?.entities||[]
  const referenced=new Set([...events.map(e=>e.attacker?.uuid),...threats.map(e=>e.uuid)].filter(Boolean))
  const entities=world.entities||[]
  const priority=e=>referenced.has(e.uuid)?0:e.type==='minecraft:player'?1:e.lineOfSight?2:3
  const candidates=entities.filter(e=>e.alive!==false && !(world.droppedItems&&e.type==='minecraft:item') && (referenced.has(e.uuid) || e.type==='minecraft:player'
    || e.lineOfSight || (e.distance<=6 && Math.abs((e.pos?.[1]??Infinity)-(origin[1]??0))<=4)))
    .sort((a,b)=>priority(a)-priority(b)||(a.distance??Infinity)-(b.distance??Infinity)||String(a.uuid).localeCompare(String(b.uuid)))
  const selected=candidates.slice(0,8).map(e=>clean({id:e.id,uuid:e.uuid,name:e.name,type:e.type,
    pos:position(e.pos),distance:number(e.distance),visible:e.lineOfSight,health:number(e.health),
    baby:e.baby,mount:bounded(e.mount),hasPassengers:e.hasPassengers,
    item:e.item?clean({name:e.item.name,count:e.item.count}):undefined}))
  const dropSource=world.droppedItems??{items:entities.filter(e=>e.type==='minecraft:item'),radius:world.entityScope?.radius,omitted:0}
  const dropped=(dropSource.items??[]).slice(0,32).map(e=>clean({id:e.id,uuid:e.uuid,pos:position(e.pos),
    distance:number(e.distance),visible:e.lineOfSight,name:e.item?.name,count:e.item?.count}))
  const nearby=world.nearbyBlocks
  const state={
    observedAt:world.observedAt??null,generatedAt:generatedAt??null,ageMs,
    stale:ageMs===null || ageMs>5000 || !world.connected,owner:world.owner??null,
    connection:clean({connected:!!world.connected,ready:!!world.ready,joining:!!world.joining,
      screen:world.screen,error:world.clientError??world.playSession?.error,reason:world.connection?.reason,
      server:world.server,dimension:world.dimension}),
    player:clean({...player,pos:position(player.pos),vehicle:bounded(player.vehicle)}),timeOfDay:world.timeOfDay??null,
    action:action(world.action),lastAction:action(world.lastAction),inventory:items(world.inventory,41),
    visibleHostiles:world.visibleHostiles??null,
    onlinePlayers:world.onlinePlayers??null,
    threatResponse:world.threatResponse??null,
    droppedItems:{items:dropped,radius:dropSource.radius??32,omitted:(dropSource.omitted??0)+Math.max(0,(dropSource.items?.length??0)-32)},
    entities:{items:selected,radius:world.entityScope?.radius??null,
      omitted:entities.length-selected.length+(world.entityScope?.omitted||0),
      truncated:!!world.entityScope?.truncated || entities.length>selected.length},
    nearbyBlocks:nearby?{radius:nearby.radius,atFeet:block(nearby.atFeet),belowFeet:block(nearby.belowFeet),
      blocks:(nearby.blocks||[]).slice(0,12).map(block),
      omittedTypes:(nearby.omittedTypes||0)+Math.max(0,(nearby.blocks?.length||0)-12),
      truncated:!!nearby.truncated || (nearby.blocks?.length||0)>12,unloadedCells:nearby.unloadedCells}:null,
    target:world.target?block(world.target):null,
    container:world.container?{type:world.container.type,title:bounded(world.container.title),
      ...items(world.container.slots),emptyStorageSlots:world.container.emptyStorageSlots,
      cursor:bounded(world.container.cursor),furnace:bounded(world.container.furnace),
      transaction:bounded(world.container.transaction)}:null,
  }
  const p=state.player,c=state.connection
  const stack=r=>`${r.count} ${short(r.name)}${r.slot!==undefined?' (slot '+r.slot+')':''}${r.maxDamage?' (durability '+(r.maxDamage-(r.damage||0))+'/'+r.maxDamage+')':''}`
  const lines=['MINECRAFT CURRENT STATE',
    `Observed: ${state.observedAt??'unknown'}. Prepared: ${state.generatedAt??'unknown'}. Age when prepared: ${ageMs===null?'unknown':ageMs+' ms'}${state.stale?' (stale)':''}.`,
    `Connection: ${c.connected?'connected':'disconnected'}; ${c.ready?'ready to act':'not ready'}${c.joining?'; joining':''}. Server: ${c.server??'unknown'}. Dimension: ${short(c.dimension)}.`,
    `You: ${text(p.name??'unknown')}; ${p.mode??'unknown mode'}; health ${p.health??'unknown'}/20; food ${p.food??'unknown'}/20; air ${p.air??'unknown'}; position ${at(p.pos)}.`,
    `Owner: ${state.owner?text(state.owner):'not set'}.`,
    `Holding: ${short(p.selectedItem)} (slot ${p.selectedSlot??'unknown'}). Dead: ${p.dead??'unknown'}. Sleeping: ${p.sleeping??'unknown'}. Submerged: ${p.submerged??'unknown'}.`,
    `World time: ${state.timeOfDay??'unknown'} ticks.`,
    'Current action: '+(state.action?JSON.stringify(state.action):'none.'),
    'Last action result: '+(state.lastAction?JSON.stringify(state.lastAction):'none recorded.'),
    'Inventory: '+(state.inventory.items.length?state.inventory.items.map(stack).join('; '):'empty.')+
      (state.inventory.omitted?` ${state.inventory.omitted} stacks omitted.`:''),
    `Nearby entities (${selected.length} shown; ${state.entities.omitted} omitted; scan radius ${state.entities.radius??'unknown'}):`]
  for(const e of selected)lines.push(`- Entity ${e.id}: ${short(e.type)} ${text(e.name)}; position ${at(e.pos)}; distance ${e.distance??'unknown'}; ${e.visible===true?'visible':e.visible===false?'not in line of sight':'visibility unknown'}${e.health!==undefined?'; health '+e.health:''}${e.item?'; drop '+stack(e.item):''}${e.baby!==undefined?'; baby='+e.baby:''}${e.mount?'; mount='+JSON.stringify(e.mount):''}${e.hasPassengers?'; occupied':''}.`)
  if(state.onlinePlayers)lines.push('Online players: '+JSON.stringify(state.onlinePlayers))
  lines.push(`Dropped items (${dropped.length} shown; ${state.droppedItems.omitted} omitted; radius ${state.droppedItems.radius}):`)
  for(const e of dropped)lines.push(`- Entity ${e.id}: ${e.count} ${short(e.name)} at ${at(e.pos)}; distance ${e.distance}; visible=${e.visible}.`)
  if(nearby) {
    lines.push(`Blocks (radius ${nearby.radius}; one nearest example per type):`,
      `At feet: ${short(nearby.atFeet?.name)} ${at(nearby.atFeet?.pos)}. Below feet: ${short(nearby.belowFeet?.name)} ${at(nearby.belowFeet?.pos)}.`)
    for(const b of state.nearbyBlocks.blocks)lines.push(`- ${short(b.name)} ${at(b.pos)} ${JSON.stringify(b.properties||{})}; ${b.count??'unknown'} cells of this type.${b.context?' Local evidence: '+JSON.stringify(b.context):''}`)
    lines.push(`Block summary: ${state.nearbyBlocks.omittedTypes} types omitted; ${nearby.unloadedCells??'unknown'} cells unloaded; partial=${state.nearbyBlocks.truncated}. Loaded blocks may be behind walls.`)
  } else lines.push('Nearby blocks: unavailable.')
  if(state.target)lines.push('Looking at: '+JSON.stringify(state.target))
  if(threats.length)lines.push('Hostiles in view (clear ray, '+world.visibleHostiles.fieldOfViewDegrees+' degree forward cone, '+world.visibleHostiles.range+' block range): '+JSON.stringify(threats.map(e=>({id:e.id,uuid:e.uuid,type:e.type,pos:position(e.pos),distance:number(e.distance)}))))
  if(p.vehicle)lines.push('Riding: '+JSON.stringify(p.vehicle))
  if(state.threatResponse?.receipt?.through)lines.push('Threat situation: '+JSON.stringify(state.threatResponse))
  if(c.screen)lines.push('Screen: '+text(c.screen))
  if(c.error||c.reason)lines.push('Connection detail: '+text(c.error||c.reason))
  if(state.container)lines.push('Open container: '+JSON.stringify(state.container))
  return {version:1,identity:world.identity,state,message:lines.join('\n')}
}

function project(batch) {
  if(!batch)return batch
  const {jobs=[],...world}=batch.world
  const summarize=j=>({name:'mc.'+j.kind,status:j.status,reason:j.reason,progress:{nativeTask:j.nativeTask,route:j.route,targets:j.targets,queue:j.queue,outcomes:j.outcomes,completed:j.done,remaining:j.left,mined:j.mined,cleared:j.cleared,removed:j.removed,alreadyClear:j.alreadyClear,digTarget:j.digTarget,adaptations:j.adaptations,held:j.held,collected:j.collected,area:j.area,item:j.item,phase:j.phase,escape:j.escape,entityId:j.entityId,entityUuid:j.entityUuid,target:j.target,approaches:j.approaches,missing:j.missing,blocked:j.blocked,usingItem:j.usingItem,scaffolding:j.scaffolding,scaffoldCleanup:j.scaffoldCleanup},result:j.result})
  world.identity={session:world.clientEpoch,life:world.life??0,server:world.server??null,dimension:world.dimension??null,clientTick:world.clientTick,playerChatId:world.playerChatId??null,
    threatResponse:world.threatResponse?.receipt??null}
  world.action=jobs.find(j=>['running','paused','cancelling'].includes(j.status))
  world.action=world.action?summarize(world.action):null
  const previous=jobs.filter(j=>!['running','paused','cancelling'].includes(j.status)).at(-1)
  world.lastAction=previous?summarize(previous):null
  const events=batch.events.map(event=> {
    if(!event.job)return event
    const {job,...rest}=event
    return {...rest,kind:rest.kind.replace('job_','action_'),action:summarize(job)}
  })
  return {...batch,world,events}
}
function offer(batch) {
  const projected=project(batch)
  return {...projected,context:context(projected.world,projected.events,batch.offeredAt??batch.world.observedAt)}
}
module.exports={context,project,offer}
