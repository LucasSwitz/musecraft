// Plan against a private inventory copy before any ingredient is consumed.
// Recipes come from this server; cycles, deep chains and search work are bounded.
module.exports=async({item,count,recipe,inventory,recipes,hasTable})=>{
  const cache=new Map()
  let visits=0
  const visit=()=>{if(++visits>10000)throw new Error('craft_plan_search_limit; request a smaller batch or prepare ingredients')}
  async function choices(name) {
    if(!cache.has(name))cache.set(name,(await recipes(name)).filter(r=>r.type==='minecraft:crafting'&&r.output.count>0&&r.ingredients.length)
      .sort((a,b)=>Number(b.fits2x2)-Number(a.fits2x2)||a.recipe.localeCompare(b.recipe)))
    return cache.get(name)
  }
  function clone(state){return {stock:{...state.stock},steps:[...state.steps]}}
  async function* consume(name,n,state,ancestors) {
    visit()
    const missing=Math.max(0,n-(state.stock[name]||0))
    if(!missing){const next=clone(state);next.stock[name]-=n;yield next;return}
    for await(const made of make(name,missing,state,ancestors)) {
      const next=clone(made);next.stock[name]-=n;yield next
    }
  }
  async function* fill(ingredients,index,state,ancestors) {
    if(index===ingredients.length){yield state;return}
    const ordered=[...ingredients[index]].sort((a,b)=>(state.stock[b]||0)-(state.stock[a]||0)||a.localeCompare(b))
    for(const alternative of ordered)for await(const next of consume(alternative,1,state,ancestors))
      yield* fill(ingredients,index+1,next,ancestors)
  }
  async function* make(name,n,state,ancestors,exact) {
    visit()
    if(ancestors.includes(name)||ancestors.length>=8)return
    for(const candidate of await choices(name)) {
      if(exact&&candidate.recipe!==exact)continue
      const batches=Math.ceil(n/candidate.output.count)
      const ingredients=Array.from({length:batches},()=>candidate.ingredients).flat()
      for await(const filled of fill(ingredients,0,state,[...ancestors,name])) {
        const next=clone(filled),produced=batches*candidate.output.count
        next.stock[name]=(next.stock[name]||0)+produced
        next.steps.push({item:name,count:produced,recipe:candidate.recipe,fits2x2:candidate.fits2x2})
        yield next
      }
    }
  }
  const initial={stock:{...inventory},steps:[]}
  let plan=null
  for await(const candidate of make(item,count,initial,[],recipe)){plan=candidate;break}
  if(plan&&!hasTable&&plan.steps.some(s=>!s.fits2x2)) {
    throw new Error('crafting_table_required; place a table with mc.build or pass existing table coordinates')
  }
  if(!plan)throw new Error(JSON.stringify({reason:'missing_crafting_supplies',item,count,
    supplies:Object.fromEntries(Object.entries(inventory).filter(([,n])=>n>0).slice(0,16)),hint:'Gather more raw supplies or inspect mc.recipes'}))
  const steps=[]
  for(const step of plan.steps) {
    const previous=steps.at(-1)
    if(previous?.recipe===step.recipe)previous.count+=step.count
    else steps.push({...step})
  }
  if(steps.length>128)throw new Error('craft_plan_step_limit; request a smaller batch')
  return steps
}
