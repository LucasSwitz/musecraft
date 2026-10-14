module.exports=async({kind,args,io,progress})=>{
  await io.check()
  const task=await io.write('nativeTaskStart',{kind,...args})
  while(true) {
    await io.check()
    const state=await io.write('nativeTaskPoll',{id:task.id})
    progress({nativeTask:state,remaining:state.remaining??null})
    if(state.status!=='running')return state
    await io.pause(150)
  }
}
