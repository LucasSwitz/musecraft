const {block}=require('./block-volume.cjs')
const {distance}=require('./navigation.cjs')

// One action remembers failed routes across its nested collect calls. Movement
// or changed local collision geometry makes an old refusal worth trying again.
module.exports=io=>{
  const attempts=io.pickupAttempts??=new Map()
  const geometry=async positions=>JSON.stringify((await io.read('blocksAt',{positions}))
    .map(b=>[b.name,b.properties]))
  return {
    async failure(drop,from) {
      const previous=attempts.get(drop.uuid)
      if(!previous)return null
      if(distance(from,previous.from)>1.5||distance(drop.pos,previous.target)>.75
          ||await geometry(previous.positions)!==previous.geometry) {
        attempts.delete(drop.uuid);return null
      }
      return previous.failure
    },
    async reject(drop,from,failure) {
      const cells=new Map()
      for(const center of [block(from),block(drop.pos)])
        for(let x=-1;x<=1;x++)for(let y=-1;y<=3;y++)for(let z=-1;z<=1;z++) {
          const p=[center[0]+x,center[1]+y,center[2]+z];cells.set(p.join(','),p)
        }
      const positions=[...cells.values()]
      attempts.set(drop.uuid,{from:[...from],target:[...drop.pos],failure,positions,geometry:await geometry(positions)})
    },
  }
}
