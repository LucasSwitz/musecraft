// Tend explicit soil blocks using the same cancellable survival primitives as
// individual tools. One action owns the whole cycle; no nested jobs or agent turns.
const {xyz}=require('./navigation.cjs')
const crops={
  'minecraft:wheat_seeds':{block:'minecraft:wheat',produce:'minecraft:wheat',age:7},
  'minecraft:carrot':{block:'minecraft:carrots',produce:'minecraft:carrot',age:7},
  'minecraft:potato':{block:'minecraft:potatoes',produce:'minecraft:potato',age:7},
  'minecraft:beetroot_seeds':{block:'minecraft:beetroots',produce:'minecraft:beetroot',age:3},
}
module.exports=async({args,io,run,progress})=>{
  const crop=crops[args.seed]
  if(!crop)throw io.fail('blocked','unsupported_crop_seed')
  const positions=[...new Map(args.positions.map(p=>[p.join(','),p])).values()]
  const plant=args.plant!==false,harvest=args.harvest!==false,budget=args.boneMeal??0
  const count=(inventory,name)=>inventory.filter(s=>s.slot<36&&s.name===name).reduce((n,s)=>n+s.count,0)
  const before=(await io.check()).inventory
  const targets=await io.read('blocksAt',{positions:positions.flatMap(p=>[p,[p[0],p[1]+1,p[2]]])})
  let seeds=0,needsHoe=false
  for(let i=0;i<positions.length;i++) {
    const soil=targets[i*2],above=targets[i*2+1]
    if(!['minecraft:farmland','minecraft:dirt','minecraft:grass_block','minecraft:dirt_path'].includes(soil.name))
      throw io.fail('blocked',`unsupported_farm_soil:${positions[i].join(',')}:${soil.name}`)
    if(!['minecraft:air',crop.block].includes(above.name))
      throw io.fail('blocked',`farm_target_occupied:${above.pos.join(',')}:${above.name}`)
    if(plant && (above.name==='minecraft:air'||harvest&&(budget>0||Number(above.properties.age)===crop.age)))seeds++
    needsHoe ||= plant&&soil.name!=='minecraft:farmland'
  }
  if(count(before,args.seed)<seeds)throw io.fail('blocked',`missing_seeds:${args.seed}; need=${seeds}`)
  const hoe=before.find(s=>s.slot<36&&s.name.endsWith('_hoe')&&s.count>0)?.name
  if(needsHoe&&!hoe)throw io.fail('blocked','missing_hoe')
  if(budget>0&&!count(before,'minecraft:bone_meal'))throw io.fail('blocked','missing_bone_meal')
  const result={tended:0,harvested:0,planted:0,boneMealUsed:0,growing:0,plots:[]}
  const block=p=>io.read('blockAt',xyz(p))
  async function use(item,position,face) {
    const value=await run('useItem',{item,position,...(face?{face}:{})})
    if(!['server_block_update','server_inventory_update'].includes(value.confirmation))
      throw io.fail('blocked','farm_effect_not_confirmed')
  }
  async function collectNear(position) {
    const pickup=await run('collect',{near:position,radius:4})
    const blocked=pickup.remaining.find(drop=>drop.item===args.seed||drop.item===crop.produce)
    if(blocked)throw Object.assign(io.fail('blocked',blocked.reason),{blocker:blocked.blocker})
  }
  for(const [i,soil] of positions.entries()) {
    await io.check()
    const pos=[soil[0],soil[1]+1,soil[2]]
    let actual=await block(pos)
    if(actual.name==='minecraft:air'&&plant) {
      if((await block(soil)).name!=='minecraft:farmland')await use(hoe,soil,'up')
      if((await block(soil)).name!=='minecraft:farmland')throw io.fail('blocked','farmland_not_confirmed')
      await use(args.seed,soil,'up');result.planted++
      actual=await block(pos)
    }
    if(actual.name!==crop.block&&actual.name!=='minecraft:air')throw io.fail('blocked','farm_target_changed')
    for(let n=0;n<budget&&actual.name===crop.block&&Number(actual.properties.age)<crop.age;n++) {
      if(!count((await io.check()).inventory,'minecraft:bone_meal'))break
      await use('minecraft:bone_meal',pos);result.boneMealUsed++
      actual=await block(pos)
    }
    if(harvest&&actual.name===crop.block&&Number(actual.properties.age)===crop.age) {
      await run('breakBlocks',{positions:[pos]});result.harvested++
      // Pick up output and seed drops before replanting; never mine an immature
      // crop merely to make the requested batch look complete.
      if(args.collect!==false) {
        await io.pause(150)
        await collectNear(pos)
      }
      if(plant){await use(args.seed,soil,'up');result.planted++}
      actual=await block(pos)
    }
    if(plant&&actual.name!==crop.block)throw io.fail('blocked','crop_not_confirmed')
    if(actual.name===crop.block&&Number(actual.properties.age)<crop.age)result.growing++
    result.plots.push({soil,crop:actual.name,age:actual.properties.age===undefined?null:Number(actual.properties.age)})
    result.tended++
    progress({completed:Array.from({length:i+1},(_,n)=>n),remaining:positions.length-i-1})
  }
  const after=(await io.check()).inventory
  result.inventoryDelta=Object.fromEntries([...new Set([...before,...after].map(s=>s.name))]
    .map(name=>[name,count(after,name)-count(before,name)]).filter(([,change])=>change))
  return {...result,confirmation:'server_block_and_inventory_updates'}
}
