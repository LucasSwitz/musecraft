module.exports=async({a,io,inv,open,progress})=>{
  const requestedItem=a.item
  const bookError=recipes=>io.fail('blocked',JSON.stringify({reason:'recipe_book_entry_missing',
    recipes:recipes.map(x=>x.recipe),retryable:false,
    hint:'Absent from player recipe book, not a server crafting ban. A table or retry cannot fix this. Needs normal discovery or operator state repair.'}))
  async function readyRecipes(request) {
    const deadline=Date.now()+1500
    for(;;) {
      const recipes=(await io.read('recipes',{item:request.item})).filter(x=>x.type==='minecraft:crafting'&&(!request.recipe||x.recipe===request.recipe))
      if(!recipes.length)throw io.fail('blocked','no_supported_recipe; use mc.recipes')
      const supplied=recipes.filter(x=>x.availableCrafts>=Math.ceil(request.count/x.output.count))
      const ready=supplied.filter(x=>x.unlocked)
      if(ready.length)return ready.sort((x,y)=>Number(y.fits2x2)-Number(x.fits2x2)||x.recipe.localeCompare(y.recipe))[0]
      if(!supplied.length)throw io.fail('blocked',JSON.stringify({reason:'missing_ingredients',item:request.item,count:request.count,
        recipes:recipes.map(x=>({recipe:x.recipe,availableCrafts:x.availableCrafts})),hint:'Gather the missing ingredients; inspect mc.recipes.'}))
      // Inventory and recipe-book synchronization arrive in separate packets.
      // Let a normal survival unlock settle, without exposing retries to the agent.
      if(Date.now()>=deadline)throw bookError(supplied)
      await io.check();await io.pause(100)
    }
  }
  async function craftOne(a) {
    let s=await inv.state()
    if(['player','minecraft:crafting'].includes(s.type))s=await inv.clearGrid()
    const recipe=await readyRecipes(a)
    const crafts=Math.ceil(a.count/recipe.output.count), produced=crafts*recipe.output.count
    const reserveOutput = state => {
      const capacity=state.slots.filter(inv.inventory).reduce((n,x)=>n+(!x.count?recipe.output.maxCount:x.name===a.item&&x.components===recipe.output.components?x.maxCount-x.count:0),0)
      if(capacity<produced)throw io.fail('blocked','inventory_full; free output space before crafting')
    }
    reserveOutput(await inv.state())
    let table=null
    if(a.table)s=await open(a.table)
    else if(!recipe.fits2x2&&s.type!=='minecraft:crafting') {
      const tables=await io.read('findBlocks',{name:'minecraft:crafting_table',radius:16})
      const doc=await io.check()
      tables.sort((x,y)=>Math.hypot(...x.pos.map((v,i)=>v-doc.pos[i]))-Math.hypot(...y.pos.map((v,i)=>v-doc.pos[i])))
      if(tables.length)table={pos:tables[0].pos}
      else throw io.fail('blocked','crafting_table_required; place a table or pass table coordinates')
      s=await open(table.pos)
    } else if(!['player','minecraft:crafting'].includes(s.type)) {await io.write('closeScreen');s=await inv.state()}
    await inv.clearGrid()
    // Reserve output space before consuming anything. Actual slot rules are also
    // checked natively for every transaction.
    s=await inv.state()
    reserveOutput(s)
    // One native action owns recipe filling, exact output collection and grid
    // cleanup. Polls renew its lease; they do not drive individual recipe steps.
    let batch=await io.write('craftBatchStart',{token:s.token,revision:s.revision,recipe:recipe.recipe,count:a.count})
    while(batch.status==='running') {
      await io.pause(150)
      batch=await io.write('craftBatchPoll',{id:batch.id})
      if(a.item===requestedItem)progress({completed:Array.from({length:batch.crafted},(_,j)=>j),remaining:batch.remaining})
    }
    if(batch.status!=='completed')throw io.fail(batch.status,batch.reason)
    if(batch.crafted!==produced)throw io.fail('blocked','unexpected_craft_count')
    await io.write('closeScreen')
    return {item:a.item,requested:a.count,crafted:batch.crafted,destination:'inventory',recipe:recipe.recipe,confirmation:'server_inventory_update',
      batch:{elapsedMs:batch.elapsedMs,transactions:batch.transactions,outputTransfers:batch.batches},...(table?{table}:{})}
  }
  if(a.prepareIngredients===false)return craftOne(a)
  const recipeGraph=await io.read('craftingGraph',{item:a.item}),completed=[]
  // Planning over the captured graph is bounded pure work. Check the action
  // before/after it instead of requesting world state for every ingredient.
  const recipes=async item=>recipeGraph[item]||[]
  const plan=async (inventory,hasTable)=>require('./craft-plan.cjs')({
    item:a.item,count:a.count,recipe:a.recipe,inventory,hasTable,recipes})
  for(let iteration=0;iteration<128;iteration++) {
    await io.check()
    const state=await inv.state()
    if(['player','minecraft:crafting'].includes(state.type))await inv.clearGrid()
    const inputs=await io.read('craftingInputs')
    const tables=a.table?[{pos:a.table}]:await io.read('findBlocks',{name:'minecraft:crafting_table',radius:16})
    const hasTable=tables.length>0||state.type==='minecraft:crafting'
    let steps
    try {steps=await plan(inputs,hasTable)}
    catch(error){throw io.fail('blocked',error.message)}
    await io.check()
    let chosen,seen=new Set(),missingBook=[]
    // A plan proves the resources exist; the vanilla matcher chooses actual
    // interchangeable inputs. Only execute a ready step that leaves a complete
    // plan feasible. For example make the oak boat before a generic chest if
    // that chest would otherwise consume the oak planks instead of birch.
    for(const step of steps) {
      if(seen.has(step.recipe))continue
      seen.add(step.recipe)
      const candidate={...step,count:Math.min(256,step.count)}
      const selection=await io.read('craftingSelection',{recipe:step.recipe,count:candidate.count})
      if(!selection.available)continue
      if(!selection.unlocked){missingBook.push(candidate);continue}
      if(step===steps.at(-1)){chosen=candidate;break}
      try {await plan(selection.after,hasTable)}
      catch {continue}
      chosen=candidate;break
    }
    if(!chosen&&missingBook.length) {
      // Revalidate the same complete plan on the next pass after book sync.
      await readyRecipes(missingBook[0])
      continue
    }
    if(!chosen)throw io.fail('blocked','craft_preparation_no_safe_step; inspect available ingredients and recipe variants')
    const final=await craftOne({...a,item:chosen.item,count:chosen.count,recipe:chosen.recipe})
    if(chosen.item===a.item) return {...final,requested:a.count,preparation:completed}
    completed.push({item:chosen.item,crafted:final.crafted})
    progress({prepared:completed,remainingSteps:steps.length-1})
    // Re-read server-confirmed inventory before planning another mutation.
  }
  throw io.fail('blocked','craft_preparation_step_limit; inspect completed inventory before continuing')
}
