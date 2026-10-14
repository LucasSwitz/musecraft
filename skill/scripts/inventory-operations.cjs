const view = s => ({
  type:s.type,title:s.title,
  slots:s.slots.filter(x=>x.role!=='inventory'&&x.role!=='equipment'&&(x.count||['input','fuel','output'].includes(x.role)))
    .map(({id,role,name,count,damage,maxDamage})=>({id,role,name,count,damage,maxDamage})),
  emptyStorageSlots:s.slots.filter(x=>x.role==='storage'&&!x.count).length,
  cursor:{name:s.cursor.name,count:s.cursor.count},
  ...(s.furnace?{furnace:s.furnace}:{}),transaction:s.transaction,
})
// Private slot mechanics. Public tools use items/counts and logical destinations.
module.exports = io => {
  const same = (a,b) => a.name===b.name && a.components===b.components
  const state = () => io.read('container')
  const guard = s => {
    if(s.cursor.count)throw io.fail('blocked','cursor_occupied; close screen to return held items')
    if(s.transaction.status==='running')throw io.fail('blocked','inventory_transaction_pending')
  }
  async function wait(started, token) {
    const until=Date.now()+8500
    while(Date.now()<until) {
      await io.check()
      const s=await state()
      if(s.token!==token)throw io.fail('cancelled','container_changed; inspect inventory before continuing')
      if(s.transaction.id!==started.id)throw io.fail('blocked','inventory_transaction_changed')
      if(s.transaction.status==='completed')return s
      if(s.transaction.status!=='running')throw io.fail('blocked',`inventory_${s.transaction.status}; inspect actual inventory, do not repeat blindly`)
      await io.pause(75)
    }
    throw io.fail('blocked','inventory_confirmation_timeout; inspect actual inventory, do not repeat blindly')
  }
  async function submit(method,s,args) {
    guard(s)
    const started=await io.write(method,{token:s.token,revision:s.revision,...args})
    return wait(started,s.token)
  }
  const count = (s,item,select) => s.slots.filter(x=>select(x)&&x.name===item).reduce((n,x)=>n+x.count,0)
  function plan(s,item,amount,source,destination,drop=false) {
    guard(s)
    const slots=s.slots.map(x=>({...x})), moves=[]
    if(count(s,item,source)<amount)throw io.fail('blocked',`missing_items:${item}; need ${amount}, have ${count(s,item,source)}`)
    let left=amount
    for(const from of slots.filter(x=>source(x)&&x.name===item&&x.count)) {
      let remaining=Math.min(left,from.count)
      if(drop) {moves.push({from:from.id,count:remaining,drop:true});left-=remaining;if(!left)break;continue}
      const dests=slots.filter(x=>x.id!==from.id&&destination(x)&&(!x.count||same(x,from)))
        .sort((a,b)=>Number(!a.count)-Number(!b.count))
      for(const to of dests) {
        const n=Math.min(remaining,Math.min(to.limit,from.maxCount)-to.count)
        if(n<=0)continue
        moves.push({from:from.id,to:to.id,count:n})
        to.name=from.name;to.components=from.components;to.maxCount=from.maxCount;to.count+=n
        from.count-=n;remaining-=n;left-=n
        if(!remaining)break
      }
      if(remaining)throw io.fail('blocked','destination_full_or_incompatible; no items moved')
      if(!left)break
    }
    return moves
  }
  async function transfer(item,amount,source,destination,progress,drop=false) {
    let s=await state()
    const token=s.token, moves=plan(s,item,amount,source,destination,drop)
    let moved=0
    for(const move of moves) {
      s=await state()
      if(s.token!==token)throw io.fail('cancelled','container_changed')
      const from=s.slots.find(x=>x.id===move.from)
      if(from.name!==item||from.count<move.count)throw io.fail('blocked','source_changed; inspect partial progress')
      s=await submit('inventoryMove',s,move)
      moved+=move.count
      progress?.({completed:Array.from({length:moved},(_,i)=>i),remaining:amount-moved})
    }
    return {item,count:moved,confirmation:'server_inventory_update',container:view(s)}
  }
  const inventory = x => x.role==='inventory'
  async function clearGrid() {
    let s=await state()
    // Only crafting inputs: taking a leftover output would craft without a request.
    for(const slot of s.slots.filter(x=>x.role==='crafting'&&x.count)) {
      await transfer(slot.name,slot.count,x=>x.id===slot.id,inventory)
      s=await state()
    }
    return s
  }
  return {view,state,guard,wait,submit,count,plan,transfer,inventory,clearGrid}
}

module.exports.view=view
