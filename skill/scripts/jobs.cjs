const crypto = require('node:crypto')
const {distance,xyz} = require('./navigation.cjs')
const copy = x => JSON.parse(JSON.stringify(x))
const point = p => {
  if(!Array.isArray(p)||p.length!==3||!p.every(Number.isFinite))throw new Error('Position must be [x,y,z] with finite numbers')
  return [...p]
}
const blockPoint = p => {p=point(p);if(!p.every(Number.isInteger))throw new Error('Block positions must be integers');return p}
const actionTimeoutMs = 10 * 60 * 1000
module.exports = ({mc,observe,launch,queueCombat,addFlee,dataDir,getPlaySession}) => {
  const plans = require('./plans.cjs')({dataDir})
  const {normalize,matches} = require('./plans.cjs')
  const inventoryKinds=['openContainer','craft','transfer','drop','moveItem','swapSlots','smelt']
  const records=new Map()
  const snapshot=r=>copy({id:r.id,playSession:r.playSession,kind:r.kind,status:r.status,reason:r.reason,startedAt:r.startedAt,updatedAt:r.updatedAt,progress:{...r.progress,completed:r.progress.completed.length},result:r.result})
  const get=id=>{const r=records.get(typeof id==='object'?id.id:id);if(!r)throw new Error('Unknown job');return r}
  const running=()=>[...records.values()].find(r=>r.status==='running'||r.status==='cancelling')
  function cancel(r,reason) {
    if(r.ac.signal.aborted)return
    r.cancelReason=reason;r.status='cancelling';r.ac.abort()
  }
  function run(r) {
    if(r.playSession !== getPlaySession())throw new Error('play_session_changed')
    if(running())throw new Error('A control job is already running; cancel it first')
    r.status='running';r.reason=null;r.cancelReason=null;r.result=null;r.updatedAt=Date.now();r.ac=new AbortController()
    const ctx={name:r.id,signal:r.ac.signal,id:r.id};r.controlContext=ctx
    const attemptStartedAt=Date.now()
    // One fixed budget per attempt, including nested mining/navigation. Progress
    // and queued targets do not renew it. Join normal cleanup before publishing
    // a terminal event or letting another action acquire control.
    const timer=['follow','flee'].includes(r.kind)?null:setTimeout(()=>{
      if(r.status==='running')cancel(r,'action_timeout')
    },actionTimeoutMs)
    timer?.unref()
    const progress=patch=>{Object.assign(r.progress,patch);r.updatedAt=Date.now()}
    r.promise=launch(ctx,r.kind,async io=>{
      const nav=require('./navigation.cjs')({io,progress})
      return execute(r,io,nav,progress)
    },r.kind==='interact'||r.kind==='wake'||r.kind==='attack'||r.kind==='flee'||r.kind==='farm'||r.kind==='gather'||r.kind==='mount'||inventoryKinds.includes(r.kind)).then(result=>{
      r.status=result.status;r.reason=result.blocker?.reason??result.reason
      const doc=result.observed
      r.result=result.status==='completed'?doc:result.navigation?result.navigation:doc?{session:doc.session,pos:doc.pos,ready:doc.ready,screen:doc.screen,heldKeys:doc.heldKeys}:null
      if(r.progress.movement?.route)r.result={...r.result,route:r.progress.movement.route}
      if(result.buildResult)r.result=result.buildResult
      if(result.blocker) {
        const {blocker,...detail}=r.result??{}
        r.result={blocker:result.blocker,...detail}
      }
      if(['goto','breakBlocks'].includes(r.kind)&&result.status==='completed')r.reason=doc.reason
      if(['gather','explore','findBlock','cleanupRoute','build','collect','attack','useItem'].includes(r.kind)&&result.status==='completed') {r.status=doc.status;r.reason=doc.reason}
    }).catch(e=>{r.status=e.status||'blocked';r.reason=e.message}).finally(()=>{
      clearTimeout(timer)
      if(r.cancelReason==='action_timeout') {
        r.status='blocked';r.reason='action_timeout'
        const {blocker,...detail}=r.result??{}
        r.result={blocker:{reason:'action_timeout',timeoutMs:actionTimeoutMs,elapsedMs:Date.now()-attemptStartedAt,
          explanation:'This action reached its 10-minute limit and was stopped. Completed work is preserved. Inspect the partial progress and current state, then choose another approach or retry the remaining work.'},...detail}
      }else if(r.status==='cancelled'&&r.cancelReason)r.reason=r.cancelReason
      r.updatedAt=Date.now();r.combatReady?.resolve(null);r.fleeReady?.resolve(null)
    })
    return snapshot(r)
  }
  function start(kind,args,options={}) {
    options=copy(options)
    if(options.mode!==undefined&&!['walk','fly','auto'].includes(options.mode))throw new Error('mode must be auto, walk or fly')
    if(options.tolerance!==undefined&&(!Number.isFinite(options.tolerance)||options.tolerance<.25||options.tolerance>2))throw new Error('tolerance must be .25..2')
    if(options.slot!==undefined&&(!Number.isInteger(options.slot)||options.slot<0||options.slot>8))throw new Error('slot must be 0..8')
    if(options.use_hands!==undefined&&typeof options.use_hands!=='boolean')throw new Error('use_hands must be boolean')
    if(running())throw new Error('A control job is already running; cancel it first')
    while(records.size>=64) {const old=[...records.values()].find(r=>!['running','cancelling','paused'].includes(r.status));if(!old)throw new Error('Job history full');records.delete(old.id)}
    const r={id:`${kind}-${crypto.randomUUID()}`,kind,args:copy(args),options,playSession:getPlaySession(),startedAt:Date.now(),progress:{completed:[],remaining:args.plan?.length??args.positions?.length??null}}
    if(kind==='attack')r.combatReady=Promise.withResolvers()
    if(kind==='flee')r.fleeReady=Promise.withResolvers()
    records.set(r.id,r);return run(r)
  }
  async function confirm(io,admission,expected) {
    if(admission.accepted===false||admission.started===false)throw io.fail('blocked','interaction_rejected')
    const until=Date.now()+Math.max(6500,(admission.expectedMs??0)+15000)
    while(Date.now()<until) {
      await io.check()
      if(admission.id) {
        const mining=await io.write('digPoll',{id:admission.id})
        if(mining.status==='completed')return mining.receipt
        if(mining.status==='blocked')throw Object.assign(io.fail('blocked',mining.reason),{blocker:mining.evidence})
      }
      const b=await io.read('blockAt',xyz(admission.target))
      if(!admission.id&&b.name===expected&&b.serverUpdate?.revision>admission.afterRevision&&b.serverUpdate.name===expected)return b
      await io.pause(100)
    }
    throw io.fail('blocked','server_block_update_not_received')
  }
  async function reachable(io, nav, target, options, action, maxRange=3.4) {
    async function attempt() {
      try{return await action()}catch(error) {
        if(!/placement_blocked_by_player/.test(error.message))throw error
        const suggested=await io.read('placementSuggestion',{...xyz(target),...options.action})
        const explanation=suggested.position
          ? 'Your body overlaps the placement. Move to suggestedPlacement.standingPosition and retry at suggestedPlacement.position. The route has not been searched.'
          : 'Your body overlaps the placement. No usable nearby placement was found. Step aside or choose another site before retrying.'
        throw Object.assign(io.fail('blocked','placement_blocked_by_player'),{blocker:{
          reason:'placement_blocked_by_player',target,explanation,
          ...(suggested.position?{suggestedPlacement:suggested}:{})}})
      }
    }
    try{return await attempt()}catch(error) {
      if(!/no_reachable_(visible_|support_)?face|entity_occluded|entity_out_of_reach|no_reachable_boat_placement/.test(error.message))throw error
      await nav.approach(target,maxRange,options)
      return attempt()
    }
  }

  async function execute(r,io,nav,progress) {
    const initial=await io.check()
    const o={...r.options}
    const materialNames=new Map()
    const materialFor=async block=>{
      if(!materialNames.has(block))materialNames.set(block,(await io.read('materialFor',{block})).name)
      return materialNames.get(block)
    }
    io.report=progress
    if(!o.mode || o.mode==='auto')o.mode=initial.canFly?'fly':'walk'
    if(['explore','findBlock'].includes(r.kind))return require('./native-task.cjs')({kind:r.kind,args:r.args,io,progress})
    if(['farm','gather'].includes(r.kind)) {
      if(initial.screen)await io.write('closeScreen')
      const run=async(kind,args,onProgress=()=>{})=>{
        try {return await execute({kind,args,options:{mode:'walk'},progress:{completed:[]}},io,nav,onProgress)}
        finally {io.report=progress;await io.stop()}
      }
      return require(r.kind==='farm'?'./farming.cjs':'./gathering.cjs')({args:r.args,io,nav,run,progress})
    }
    if(inventoryKinds.includes(r.kind))return require('./inventory-jobs.cjs')({r,io,nav,progress,options:o,reachable,materialFor})
    if(r.kind==='cleanupRoute') {
      const plan=await io.read('routeCleanupPlan',r.args)
      if(!plan.length)return {status:'completed',reason:'already_clean',routeId:r.args.routeId,remaining:0}
      const state=await require('./building.cjs')({r:{...r,args:{plan,planId:'route-'+r.args.routeId}},io,progress,options:{mode:'walk',routeCleanup:true},dataDir})
      const remaining=await io.read('routeCleanupPlan',r.args)
      if(remaining.length)throw io.fail('blocked','route_cleanup_incomplete')
      return {...state,routeId:r.args.routeId,remaining:0}
    }
    if(r.args.schematic) {
      const imported=await io.read('importSchematic',r.args.schematic)
      const plan=plans.register(r.args.planId,imported.blocks,{creativeSupply:o.creativeSupply,mode:o.mode})
      r.args.plan=plan.blocks
      progress({schematic:{backend:imported.backend,dimensions:imported.dimensions,cells:plan.blocks.length}})
    }
    if(r.args.planId)plans.bind(r.args.planId,initial)
    if(r.kind==='verifyBuild') {
      let matched=0;const mismatches=[]
      for(let start=0;start<r.args.plan.length;start+=128) {
        await io.check()
        const batch=r.args.plan.slice(start,start+128)
        const blocks=await io.read('blocksAt',{positions:batch.map(item=>item.pos)})
        for(let i=0;i<batch.length;i++) {
          const actual=blocks[i],item=batch[i]
          if(matches(actual,item))matched++
          else mismatches.push({pos:item.pos,expected:{block:item.block,state:item.state},actual:{block:actual.name,state:actual.properties},conflict:!actual.replaceable})
        }
        progress({completed:Array.from({length:matched+mismatches.length},(_,i)=>i),remaining:r.args.plan.length-matched-mismatches.length})
      }
      return {planId:r.args.planId,total:r.args.plan.length,matched,correct:mismatches.length===0,mismatches:mismatches.slice(0,64),mismatchCount:mismatches.length,truncated:mismatches.length>64}
    }
    if(['collect','pillar','mount','dismount','wake','flee'].includes(r.kind))return require('./mobility-jobs.cjs')({r,io,nav,progress,confirm});
    if(r.kind==='build')return require('./building.cjs')({r,io,progress,options:o,dataDir})
    if(r.kind==='captureBuild')return require('./camera.cjs')({io,nav,progress,plan:plans.get(r.args.planId),options:o})
    if(r.kind==='useItem') {
      await io.write('selectItem',{item:r.args.item})
      const request={item:r.args.item,...(r.args.holdMs===undefined?{}:{holdMs:r.args.holdMs}),...(r.args.face?{face:r.args.face}:{})}
      let target=r.args.position
      if(target&&/^minecraft:.*_(boat|raft)$/.test(r.args.item)) {
        const column=await io.read('blocksAt',{positions:Array.from({length:16},(_,i)=>[target[0],target[1]+i,target[2]])})
        if(column[0].name==='minecraft:water') {
          let top=0;while(top+1<column.length&&column[top+1].name==='minecraft:water')top++
          target=[target[0],target[1]+top,target[2]]
        }
      }
      if(target)Object.assign(request,xyz(target))
      if(r.args.entityId!==undefined) {
        const entity=initial.entities.find(e=>e.id===r.args.entityId)
        if(!entity)throw io.fail('blocked','target_gone')
        Object.assign(request,{entityId:entity.id,uuid:entity.uuid});target=entity.pos
      }
      const started=target?await reachable(io,nav,target,{...o,action:{useItem:true,item:request.item,...(request.face?{face:request.face}:{})},...(request.entityId===undefined?{}:{entityId:request.entityId,uuid:request.uuid})},()=>io.write('useItemStart',request),r.args.position?3.4:2.1):await io.write('useItemStart',request)
      while(true) {
        const result=await io.write('useItemPoll',{id:started.id})
        if(result.status==='completed'||result.status==='blocked') {
          const counts=stacks=>stacks.reduce((all,s)=>{all[s.name]=(all[s.name]||0)+s.count;return all},{})
          const before=counts(result.inventoryBefore||[]),after=counts(result.inventoryAfter||[])
          const inventoryDelta=Object.fromEntries([...new Set([...Object.keys(before),...Object.keys(after)])]
            .map(name=>[name,(after[name]||0)-(before[name]||0)]).filter(([,change])=>change))
          return {status:result.status,reason:result.reason,...(result.explanation?{explanation:result.explanation}:{}),item:result.item,confirmation:result.confirmation,changedBlocks:result.changedBlocks,released:result.released,inventoryDelta}
        }
        progress({item:r.args.item,usingItem:result.usingItem,elapsedMs:result.elapsedMs})
        await io.pause(100)
      }
    }
    if(r.kind==='goto') {
      const state=await nav.go(r.args.position,{...o,goal:'near',...(r.args.entityId===undefined?{}:{entityId:r.args.entityId})})
      const displacement=distance(initial.pos,state.pos)
      // Baritone records the first physical movement, independently of search
      // completion. Do not infer a no-op from endpoint distance alone.
      const alreadyInRange=state.firstMotionMs===null&&displacement<=.1
      const radius=o.distance??(r.args.entityId===undefined?null:2.5)
      const arrival=radius===null?{kind:'nearby_ground',horizontalRadius:2.5,verticalTolerance:1.25}
        :{kind:'distance',radius}
      return {reason:alreadyInRange?'already_in_range':'arrived',alreadyInRange,displacement,arrival,
        explanation:alreadyInRange
          ? 'Already within the accepted arrival region; no movement was needed. For a closer coordinate destination, set distance (minimum 0.25 blocks).'
          : `Reached the accepted arrival region; final position is ${displacement.toFixed(2)} blocks from the starting position.`,
        ...Object.fromEntries(Object.entries(state).filter(([key])=>key!=='reason'))}
    }
    if(r.kind==='attack') {
      if(initial.container)await io.write('closeScreen')
      const entity=initial.entities.find(e=>e.id===r.args.entityId)
      if(!entity?.alive)throw io.fail('blocked','target_gone')
      const started=await io.write('combatStart',{entityId:entity.id,uuid:entity.uuid,...(o.weapon?{weapon:o.weapon}:{})})
      r.combatReady.resolve(started.id)
      while(true) {
        await io.check()
        const state=await io.write('combatPoll',{id:started.id})
        progress({entityId:state.entityId,uuid:state.uuid,target:state.target,strikes:state.strikes,
          health:state.lastHealth,guarding:state.guarding,queue:state.queue,outcomes:state.outcomes})
        if(['completed','partial'].includes(state.status))return {status:state.status,reason:state.reason,
          outcome:state.outcome,strikes:state.strikes,lastHealth:state.lastHealth,shieldEquipped:state.shieldEquipped,
          outcomes:state.outcomes,omittedOutcomes:state.omittedOutcomes}
        if(state.status!=='running')throw io.fail(state.status,state.reason)
        if(state.needsApproach&&state.target) {
          try {await nav.approach(state.target,2,{...o,oneStep:true,entityId:state.entityId,uuid:state.uuid})}
          catch(error) {
            // A death can end the old navigation while the mod advances its
            // queue. Only a failure for the still-current target blocks combat.
            const latest=await io.write('combatPoll',{id:started.id})
            if(latest.status==='running'&&latest.uuid===state.uuid)throw error
          }
        }
        await io.pause(100)
      }
    }
    if(r.kind==='follow') {
      let identity=r.args.uuid||null
      while(true) {
        const doc=await io.check(),e=doc.entities.find(e=>e.id===r.args.entityId)
        if(!e?.alive)throw io.fail('blocked','target_gone')
        if(!identity)identity=e.uuid
        if(identity!==e.uuid)throw io.fail('cancelled','entity_identity_changed')
        const gap=distance(doc.pos,e.pos),range=o.distance??2.5
        progress({entityId:e.id,uuid:identity,target:e.pos,distance:gap})
        if(gap>range+.4) {await nav.approach(e.pos,range,{...o,oneStep:true,entityId:e.id,uuid:identity});continue}
        await io.stop()
        await io.pause(200)
      }
    }
    if(r.kind==='interact') {
      const before=await io.check();let request
      if(r.args.entityId!==undefined) {
        const e=before.entities.find(e=>e.id===r.args.entityId);if(!e)throw io.fail('blocked','target_gone')
        request={entityId:e.id,uuid:e.uuid}
      } else {request={...xyz(r.args.position),action:r.args.action}}
      const target=r.args.position || before.entities.find(e=>e.id===r.args.entityId).pos
      const result=await reachable(io,nav,target,{...o,...(request.entityId===undefined?{}:{entityId:request.entityId,uuid:request.uuid})},()=>io.write('interactTarget',request),r.args.position?3.4:2.1)
      if(result.accepted===false)throw io.fail('blocked','interaction_rejected')
      if(r.args.action) {
        const until=Date.now()+5000
        while(true) {
          const block=await io.read('blockAt',xyz(r.args.position))
          if(block.properties.open===String(r.args.action==='open'))return {...result,confirmation:'observed_open_state',state:block.properties}
          if(Date.now()>until)throw io.fail('blocked','interaction_state_not_confirmed')
          await io.check();await io.pause(100)
        }
      }
      await io.pause(300);const after=await io.check()
      return {...result,confirmation:after.container?'server_container_open':after.screen?'screen_open':'submitted',screen:after.screen,container:after.container}
    }
    return require('./mining.cjs')({r,io,nav,progress,confirm,options:o})
  }
  const api={
    flee:async request=>{
      const r=running()
      if(!r)return start('flee',request,{mode:'walk'})
      if(r.kind!=='flee'||r.status!=='running')throw new Error('flee_not_running')
      const id=await r.fleeReady.promise
      if(!id||r.status!=='running'||r.controlContext.signal.aborted)throw new Error('flee_not_running')
      const state=await addFlee({id,...request},r.controlContext)
      Object.assign(r.progress,{entityId:state.entityId,uuid:state.uuid,targets:state.targets,
        escape:{revision:state.phaseRevision,distance:state.distance,safeDistance:state.safeDistance}})
      r.updatedAt=Date.now();return snapshot(r)
    },
    explore:request=>start('explore',request,{mode:'walk'}),
    findBlock:request=>start('findBlock',request,{mode:'walk'}),
    gather:request=>start('gather',{...request,...(request.near?{near:point(request.near)}:{})},{mode:'walk'}),
    farm:request=>start('farm',{...request,positions:request.positions.map(blockPoint)},{mode:'walk'}),
    collect:request=>start('collect',request,{}),
    mount:request=>start('mount',request,{}),
    wake:()=>start('wake',{},{}),
    dismount:()=>start('dismount',{},{}),
    cleanupRoute:request=>start('cleanupRoute',request,{mode:'walk'}),
    pillar:request=>start('pillar',request,{}),
    useItem:(request,o={})=>{
      if(!request || typeof request.item!=='string' || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(request.item))throw new Error('Use {item:"minecraft:item",target?,face?,holdMs?}')
      if(request.holdMs!==undefined && (!Number.isSafeInteger(request.holdMs)||request.holdMs<50||request.holdMs>60000))throw new Error('holdMs must be 50..60000')
      if(request.face!==undefined&&!['up','down','north','south','east','west'].includes(request.face))throw new Error('Invalid block face')
      const args={item:request.item,...(request.holdMs===undefined?{}:{holdMs:request.holdMs}),...(request.face?{face:request.face}:{})}
      if(request.target!==undefined) {
        if(Array.isArray(request.target))args.position=blockPoint(request.target)
        else if(Number.isInteger(request.target?.entityId))args.entityId=request.target.entityId
        else throw new Error('Target must be [x,y,z] or {entityId}')
      }
      if(request.face && !args.position)throw new Error('face requires a block target')
      return start('useItem',args,o)
    },
    goto:(p,y,z,options)=>p?.target!==undefined?start('goto',Number.isSafeInteger(p.target)?{entityId:p.target}:{position:point(p.target)},{...p,...y}):Array.isArray(p)?start('goto',{position:point(p)},y):start('goto',{position:point([p,y,z])},options),
    follow:(entityId,o={})=>{if(!Number.isInteger(entityId)||!Number.isFinite(o.distance??2.5)||(o.distance??2.5)<1||(o.distance??2.5)>8)throw new Error('Use entity ID and distance 1..8');return start('follow',{entityId},o)},
    attack:(entityId,o={})=>{if(typeof entityId==='object')entityId=entityId.entityId;if(!Number.isInteger(entityId))throw new Error('Use numeric entity ID');return start('attack',{entityId},o)},
    queueAttack:async entityId=>{
      if(!Number.isInteger(entityId))throw new Error('Use numeric entity ID')
      const r=running()
      if(!r)return start('attack',{entityId},{})
      if(r.kind!=='attack'||r.status!=='running')throw new Error('attack_not_running')
      const id=await r.combatReady.promise
      if(!id||r.status!=='running'||r.controlContext.signal.aborted)throw new Error('attack_not_running')
      const doc=await observe(),entity=doc.entities.find(e=>e.id===entityId)
      if(!entity?.alive)throw new Error('target_unavailable')
      const state=await queueCombat({id,entityId:entity.id,uuid:entity.uuid},r.controlContext)
      Object.assign(r.progress,{entityId:state.entityId,uuid:state.uuid,queue:state.queue,outcomes:state.outcomes})
      r.updatedAt=Date.now();return snapshot(r)
    },
    build:(request,o={})=>{
      if(Array.isArray(request))return start('build',{plan:normalize(request)},o)
      if(!request || !request.planId)throw new Error('Use {planId,blocks,materials}')
      if(request.materials!==undefined&&!['creative','inventory'].includes(request.materials))throw new Error('materials must be inventory or creative')
      if(request.schematic)return start('build',{planId:request.planId,schematic:request.schematic},{...o,mode:request.mode??o.mode,creativeSupply:request.materials==='creative'})
      const plan=plans.register(request.planId,request.blocks,{creativeSupply:request.materials==='creative',mode:request.mode??'auto'})
      return start('build',{plan:plan.blocks,planId:plan.id},{...o,mode:request.mode??o.mode,creativeSupply:request.materials==='creative'})
    },
    verifyBuild:planId=>{const plan=plans.get(planId);return start('verifyBuild',{plan:plan.blocks,planId},{})},
    repairBuild:(planId,o={})=>{const plan=plans.get(planId);return start('build',{plan:plan.blocks,planId},{...plan.options,...o})},
    captureBuild:(planId,o={})=>{plans.get(planId);return start('captureBuild',{planId},o)},
    breakBlocks:(positions,o={})=>{if(!Array.isArray(positions)||!positions.length||positions.length>4096)throw new Error('Use 1..4096 positions');return start('breakBlocks',{positions:positions.map(blockPoint)},o)},
    interact:(target,o={})=>{if(target?.target)return start('interact',Array.isArray(target.target)?{position:blockPoint(target.target),action:target.action}:{entityId:target.target},{...o,mode:target.mode??o.mode});if(typeof target==='number'&&!Number.isInteger(target))throw new Error('Use an integer entity ID');return start('interact',typeof target==='number'?{entityId:target}:{position:blockPoint(target)},o)},
    jobs:{
      list:()=>[...records.values()].map(snapshot),
      status:id=>snapshot(get(id)),
      cancel:async (id,reason='job_cancelled')=>{const r=get(id);if(r.status==='running'||r.status==='cancelling'){cancel(r,reason);await r.promise}else if(r.status==='paused'){r.status='cancelled';r.reason=reason;r.updatedAt=Date.now()};return snapshot(r)},
      resume:id=>{const r=get(id);if(r.status!=='paused')throw new Error('Only paused jobs can resume');r.progress.missing={};return run(r)},
      wait:async(id,ms=20000)=>{if(!Number.isFinite(ms)||ms<0||ms>25000)throw new Error('Wait must be 0..25000ms');const r=get(id);let timer;await Promise.race([r.promise,new Promise(res=>{timer=setTimeout(res,ms)})]);clearTimeout(timer);return snapshot(r)},
    },
  }
  for(const kind of inventoryKinds)api[kind]=(request)=>start(kind,request,{})
  return {api,list:()=>[...records.values()].slice(-8).map(r=>({id:r.id,playSession:r.playSession,kind:r.kind,status:r.status,reason:r.reason,progress:{...r.progress,completed:r.progress.completed.length},...((['gather','build'].includes(r.kind)||r.reason==='action_timeout')&&r.result?{result:r.result}:{}),updatedAt:r.updatedAt})),abortAll:()=>{for(const r of records.values())if(r.status==='running')r.ac.abort()}}
}
