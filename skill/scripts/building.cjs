const fs=require('node:fs'),path=require('node:path')

// The controller owns the durable public plan and action lease. Construction is
// one native Baritone process; there is no per-block scheduling or movement here.
module.exports=async({r,io,progress,dataDir,options})=>{
  let state=null,id=null
  const save=()=>{
    const dir=path.join(dataDir,'build-results');fs.mkdirSync(dir,{recursive:true,mode:0o700})
    const file=path.join(dir,r.id+'.json'),fd=fs.openSync(file+'.tmp','w',0o600)
    try{fs.writeFileSync(fd,JSON.stringify({jobId:r.id,playSession:r.playSession,result:state}));fs.fsyncSync(fd)}finally{fs.closeSync(fd)}
    fs.renameSync(file+'.tmp',file)
  }
  try {
    state=await io.write('buildStart',{planId:r.args.planId??r.id,items:r.args.plan,creativeSupply:options?.creativeSupply===true,routeCleanup:options?.routeCleanup===true})
    id=state.id
    for(;;) {
      progress({completed:Array.from({length:state.verified},(_,i)=>i),remaining:state.remaining,
        phase:state.phase,backend:state.backend,changed:state.changed,missing:state.missing,cleanup:state.cleanup})
      if(state.status!=='running')break
      state=await io.write('buildPoll',{id})
      if(state.status==='running')await io.pause(200)
    }
  }catch(error) {
    // Terminal state survives respawn and cancellation. Do not replace a native
    // player_died result with the later UI/session/owner error.
    try {
      const native=await io.read('buildStatus')
      if(id&&native.id===id){state=native;if(state.status==='running'){await io.stop();state=await io.read('buildStatus')}}
    }catch{}
    if(!state||state.status==='running')state={planId:r.args.planId??null,status:error.status==='cancelled'?'cancelled':'blocked',
      reason:String(error.code||error.message).replace(/^java\.lang\.\w+: /,''),complete:false,
      total:r.args.plan.length,verified:state?.verified??0,remaining:r.args.plan.length-(state?.verified??0),
      changed:state?.changed??{placed:0,cleared:0},blockers:[],missing:{},cleanup:{remaining:0,targets:[]}}
  }
  save();progress({completed:Array.from({length:state.verified},(_,i)=>i),remaining:state.remaining,phase:'finished',buildResult:state})
  if(state.status!=='completed')throw Object.assign(io.fail(state.status,state.reason),{buildResult:state})
  return state
}
