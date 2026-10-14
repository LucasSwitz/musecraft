# Musecraft command and action reference

The player plays through one shell command, `musecraft`. This reference covers
the session commands, every gameplay action, the results and blockers they
return, and the events that wake the player.

Actions take JSON objects whose exact schemas come from `scripts/tool-api.cjs`.
`musecraft ACTION key=value ...` validates input before sending it, and
`musecraft help ACTION` prints one schema. No JavaScript is evaluated from agent
input. Sections below name actions by their controller method, such as
`mc.gather({item:"minecraft:oak_log",count:8})`; on the command line, drop the
`mc.` prefix and pass the same fields: `musecraft gather item=minecraft:oak_log
count=8`.

## Commands

| Command | What it does |
| --- | --- |
| `musecraft play [server=HOST:PORT] [player=NAME] [owner=NAME] [client=JSON]` | Start or resume play. The first run needs `server`, `player` and `owner` (the Minecraft name of the person the player follows); settings are saved for later runs. |
| `musecraft client` | Run the game client configured by `client`. Keep it running in the background; it exits only if the game does. |
| `musecraft login [stop]` | Open Prism without the game for the one-time Microsoft sign-in, as a background command; `stop` closes it. See [setup.md](../setup.md#2-sign-in). |
| `musecraft accounts [keep NAME]` | List the signed-in Minecraft accounts by name. `keep NAME`, with Prism closed, signs out every other account and makes NAME the default that plays. |
| `musecraft screen shot \| click X Y \| type TEXT \| key NAME[+NAME]` | See the client display (prints a PNG path to read) and click or type on it, for sign-in and other launcher dialogs. |
| `musecraft standby` | Keep running in the background between turns. It exits, with the events, when something needs the player. |
| `musecraft wait [seconds]` | Wait until something needs the player (default 300, at most 3600). |
| `musecraft state` | Print the full current state. |
| `musecraft stop [reason=TEXT]` | End play and disconnect. |
| `musecraft help [ACTION]` | List every action, or print one action's parameters. |
| `musecraft ACTION key=value ...` | Run one action from the tables below. |

`screenshot` and `capture` are aliases for `screen.capture`.

### Action input

Each `key=value` value is parsed as JSON when it parses and kept as text
otherwise: `count=8` is a number, `target=[120,64,-35]` an array,
`item=minecraft:oak_log` text. Quote text that would parse as JSON, for example
`text='"42"'`. Nested objects can be passed as JSON values
(`request='{"item":"minecraft:bread"}'`) or as one JSON object argument:

```sh
musecraft useItem '{"request":{"item":"minecraft:bread"}}'
```

Invalid input is rejected before anything is sent, with that action's help.

### Output

Each command prints, in order:

- `RESULT {...}`: the action's result (not printed by `state`, `wait` or
  standby);
- a note such as `Playing on HOST as PLAYER with OWNER.` or
  `Minecraft needs you: chat from OWNER.`;
- `EVENTS (oldest first):` and one line per event pending since the last output;
  chat events are `{kind:"chat",from,text,at}`;
- a one-line `NOW` status with health, food, position, readiness, the running
  action and where the owner is (entity ID and distance when nearby). `state`,
  joining, death, respawn and reconnection print the full state instead;
- reminders when the managed client is not running or standby is off.

Output printed by one command is acknowledged by the next. A physical action
keeps the command open until it finishes, blocks or something else needs the
player, for at most 15 minutes; after that the action continues and its outcome
wakes the player. `follow`, `flee`, `attack` and `queueAttack` return as soon
as they start; their outcomes wake the player. A decision made from an outdated
view (`observed_world_changed`, `observed_life_changed`) is rejected and the
command prints the fresh state.

A physical action or `wait` that starts within a second of a command's output
with waking events is the rest of the same shell chain: no player has read that
output yet. It is skipped with
`{ok:false,status:"skipped",reason:"events_need_review"}` and a note to read
the events and decide again. Chat and reads still run.

### Waiting, standby and wake-ups

These events need the player and wake it:

- chat from the owner, or chat that contains the player's name;
- danger: damage (except follow-up hits in a burst) or an escalating threat;
- an action the player started finishing, blocking or being interrupted;
- death, respawn, joining and reconnection;
- the owner joining or leaving the server.

Other events (other players' chat, presence changes, morning, threat updates)
wait and arrive with the next output. Being idle never wakes the player.

`musecraft standby` runs in the player's background and exits about half a
second after a waking event, once no command is open and 1.5 seconds have
passed since the last one; a turn still issuing commands sees the events in
their output instead. It also exits when the managed client is down. Its output
starts with `Minecraft needs you: REASON` and carries the pending events and
status, so the player acts without another command. It survives controller
restarts (restarting the controller if needed) and ends by itself with `Play is
not active, so standby ended.` when play stops. Only one standby runs at a
time; a second one exits at once and is not a wake-up.

`musecraft wait [seconds]` blocks in the foreground by the same rule, returning
with the events or after `seconds`.

### Sessions and recovery

`musecraft play` gives the play session to the controller, so play continues
without renewals while the controller runs. It saves `server`, `player`,
`owner` and `client` in `cli-config.json` in the home directory. `owner` is the
Minecraft name of the person the player follows; the state shows it as `Owner`,
and `musecraft play owner=NAME` changes it during play. Run
`musecraft stop` before changing the server or player: `play` refuses a
different definition while play is active (`play_session_definition_changed`).
A blocked session (wrong account, version or allowlist) makes `play` fail with
`play_blocked: REASON`; resolving it is a setup task (see `setup.md`).

The controller retains action receipts, event acknowledgement and the CLI cursor
(`cli-state.json`); the side chat retains conversation history across restarts.
There is no agent-facing checkpoint step. A controller restart drops an open
command; the player reruns `musecraft wait` or standby wakes it, and the next
command prints fresh world state under the ordinary action-reconciliation rules.
`musecraft stop` ends play.

With `client` configured, the player runs `musecraft client`, a long-running
command that holds the Minecraft client inside the player's managed exec (the
client needs that exec's egress). Command output and standby report when it is
not running.

## Actions

Physical actions start, or replace, the player's one running action. Reads and
chat leave it running.

**Movement**

| Action | Arguments |
| --- | --- |
| `goto` | `{target:[x,y,z] \| entityId, distance?:0.25..8, mode?, descend?, allowBreak?, protectedBlocks?, allowPlace?, maxPlace?:1..128, placeBlock?}` |
| `follow` | `{entityId, options?:{distance?:1..8,mode?}}` |
| `explore` | `{near?,radiusChunks?:0..16}`; native exploration with measured chunk coverage |
| `findBlock` | `{block,near?,radius?:1..256}`; native cached search and approach, leaves containers closed |
| `pillar` | `{height:1..32,block}`; native Baritone pillaring, bounded to height placements; preserves headroom |
| `cleanupRoute` | `{routeId}`; native construction cleanup of journaled route blocks |
| `mount` | `{entityId,prepare?:true}`; board a boat, or tame/saddle and board an adult horse |
| `dismount` | `{}`; detach and confirm the riding relationship |
| `wake` | `{}`; leave a bed and confirm awake state |

**Mining, gathering and farming**

| Action | Arguments |
| --- | --- |
| `gather` | `{item,count,near?,radius?:1..32}`; discover, mine and collect within a fixed area |
| `breakBlocks` | `{positions:[[x,y,z],...], use_hands?:false, options?}` |
| `collect` | `{near?:[x,y,z],radius?:1..32}`; pick up all drops in a fixed area |
| `farm` | `{positions:[soilPositions],seed,plant?:true,harvest?:true,collect?:true,boneMeal?:0..8}` |

**Building and protection**

| Action | Arguments |
| --- | --- |
| `build` | `{request:{planId,blocks\|schematic,materials?:"inventory"\|"creative",mode?} \| blocks, options?}` |
| `repairBuild` | `{planId, options?}` |
| `verifyBuild` | `{planId}` |
| `captureBuild` | `{planId, options?:{view:"exterior",hideHud?,mode?}}` |
| `protectArea` | `{min:[x,y,z],max:[x,y,z],label}`; durable protected box (see [protection](#resource-and-build-protection)) |

**Items, crafting and containers**

| Action | Arguments |
| --- | --- |
| `useItem` | `{request:{item,target?:[x,y,z]\|{entityId},face?,holdMs?}, options?}` |
| `equip` | `{item:"minecraft:iron_axe"}` |
| `interact` | `{target:[x,y,z] \| entityId \| {target,action:"open"\|"close",mode?}, options?}` |
| `craft` | `{item,count:1..256,table?:[x,y,z],prepareIngredients?:boolean,recipe?}`; additional output count, rounded up to complete recipe batches |
| `smelt` | `{target:[x,y,z],item,count,fuel:{item,count},wait?:true}`; load, cook and collect |
| `openContainer` | `{target:[x,y,z]}`; approach, open and inspect |
| `transfer` | `{item,count,direction:"deposit"\|"withdraw",target?,section?:"storage"\|"input"\|"fuel"\|"output"}` |
| `drop` | `{item,count,slot?}`; exact amount from inventory |
| `moveItem` | `{item,count,toSlot,fromSlot?}`; move or merge without replacing different items |
| `swapSlots` | `{fromSlot,toSlot}`; exchange complete stacks |
| `closeScreen` / `respawn` | `{}` |

**Combat**

| Action | Arguments |
| --- | --- |
| `attack` | `{entityId, options?:{weapon?,mode?}}`; attack now and replace current work |
| `queueAttack` | `{entityId}`; append without interrupting combat |
| `flee` | `{entityId, distance?:8..64}` or `{entityIds:[...], distance?:8..64}` (default 40) |

**Reads, chat and appearance** (these leave the running action alone, except `animate`)

| Action | Arguments |
| --- | --- |
| `chat` | `{text}`; ordinary chat, no slash commands |
| `inventory` | `{}`; complete inventory |
| `entities` | `{}`; `{connected,entities,scope}` containing the full native nearby sample, including entity IDs and mount details |
| `blockAt` | `{x,y,z}` |
| `findBlocks` | `{names:[...],radius?:1..16,limit?:16..64}`; nearest loaded matches for 1–16 block IDs or tags, including behind walls |
| `scanBlocks` | `{name?,names?,radius?:1..128,limit?:1..64}`; visible blocks in 360 degrees (defaults radius 128, limit 32) |
| `inspectRegion` | `{min,max,names?,limit?:1..128}`, or `{scanId,offset,limit?}` for the next page |
| `recipes` | `{item}`; recipes for an output, ingredients, batch size, unlocks and available crafts |
| `container` | `{}`; typed slots, contents, cursor, furnace progress |
| `screenshot` | `{}`; capture without moving (`screen.capture`) |
| `animations` | `{}` |
| `animate` | `{name,value?:0..255}`; a physical action |

## Common rules

Options use `mode:"auto"|"walk"|"fly"`, `tolerance` 0.25–2, hotbar `slot` 0–8
and `creativeSupply` where supported. A block entry is
`{pos:[x,y,z],block:"minecraft:oak_log",state?:{axis:"y"},replace?}`.

Finite gameplay actions have a fixed 10-minute limit per attempt, including
nested navigation and obstruction clearing. Progress and queued combat targets
do not extend it. Expiry stops controls and sends an action-blocked event with
`reason:"action_timeout"`, partial progress, and `result.blocker` containing a
plain explanation, `timeoutMs:600000`, and `elapsedMs`. Completed world changes
are preserved; choose another approach or retry the remaining work
(`mc.repairBuild` for a saved build). Continuous `mc.follow` and `mc.flee` run
until replaced or ended. Actions do not accept a caller-supplied `timeoutMs`.
RPC response bounds, server-confirmation checks, lack-of-progress detection and
ownership leases can still stop an action earlier.

Plans and mining accept 1–4096 positions. Plans ignore transient door, powered,
lit and occupied state during validation. Item holds range from 50 to 60000 ms.
Food consumes one use; target interactions may only confirm submission. Mining
confirms server block updates, not pickup of drops. An entity disappearing does
not prove it was killed.

### Replacement and evidence

Every physical action (including verification, camera movement and animations)
replaces the previous physical action. Chat, inventory, observation, block
queries, animation listing and a stationary screenshot leave it running.
Ending a turn leaves the current action unchanged; it neither cancels work nor
waits for completion. The controller serializes admissions, fences actions by
session generation and observed world identity, and rejects late requests. A
`running` response means started, not completed. An identical action repeated
from an older queued frame returns the original action's current outcome with
`coalesced: true`. It neither cancels the action nor repeats inventory effects.
Fresh observations and new player requests allow intentional repeats; the
controller supplies this context automatically.

Observations carry one current action and ordered completion/blocker events,
including results such as build correctness, item-use confirmation and capture
paths. Inventory is a complete snapshot; entity observations are bounded to
32 entities within 32 blocks. Observe gap events and inspect fresh state after
uncertain outcomes. Named plans can be repaired after a controller restart.

### Search volumes

`findBlocks`, `scanBlocks`, `gather` and `collect` use inclusive whole-block
cubes: `min = floor(center) - radius`, `max = floor(center) + radius` on each
axis. Negative coordinates round down. Radius 1 covers 27 cells. Gathering keeps
its center fixed for source discovery, access clearance and pickup; collect uses
`floor(near)` or its starting block. Visibility still requires a clear ray from
the eyes. Movement arrival distance, combat reach and hostile warning distance
remain physical distances rather than search volumes.

## Movement

### Coordinates and arrival

For walking to fixed coordinates, `distance:0.25..8` is a 3D arrival radius
with safe footing, including across obstacles. Use `distance:0.25` for a precise
short move. Without it, goto accepts nearby safe ground within 2.5 horizontal
and 1.25 vertical blocks and can project air targets down up to 16 blocks.
Entity targets track the live position with distance 1..8 (default 2.5).
`tolerance` is an internal movement option, not a public goto argument.

Coordinate `mc.goto` lets Baritone search block-level destination cells and
choose tool-assisted excavation when `allowBreak:true`. Access block placement
requires `allowPlace:true` and respects its placement budget. Arrival checks
the requested radius against the real feet position; a sub-block radius uses
final alignment instead of requiring a block center inside that radius. Omit
`distance` for approximate destinations and downward air projection. Coordinate
walking arrival measures spatial proximity and safe footing, including across
obstacles. Use a strict small radius to enter a particular place. Entity and
interaction goals retain their own visibility checks.

Completed goto results carry `{reason, alreadyInRange, displacement, arrival,
explanation, ...navigation}`. `already_in_range` confirms a walking no-op;
`arrived` reports reaching the accepted region. `displacement` is the
straight-line distance from the starting position, not total distance walked.
`arrival` is either `{kind:"distance",radius}` or
`{kind:"nearby_ground",horizontalRadius,verticalTolerance}`.

`planning.searchAttempted` distinguishes native route search from endpoint
rejection; `planning.rejectedDestinations` counts cells that failed final
alignment; `planning.candidateCells` counts the remaining candidate cells,
including the boundary fallback. `destination_not_standable` includes
`nearestStandingPosition` and `arrivalRadius` when nearby observed footing lies
outside a tight requested radius. `destination_alignment_unavailable` means all
candidate cells failed final placement of the player's body. These are distinct
from `no_route_in_region`. Runtime death/stop behavior is unchanged.

Navigation blockers have `{reason,cause,position,target,goal,heightDifference,
explanation}` and optionally `{minimumRise,suggestedTool:"mc.pillar"}`.
`minimumRise` is a geometric lower bound for action height, not a verified
pillar route or material requirement.

### Entities

Entity `mc.goto` tracks the live entity until within `distance` (default 2.5
blocks) and visible. It then completes; `mc.follow` keeps following. Entity
identity is pinned for the action; a disappearing or replaced entity blocks
instead of using its last position. Dismount boats before entity navigation.

### Changing terrain on the way

Walking preserves terrain by default. `allowBreak:true` permits native
excavation; `protectedBlocks` uses Baritone's hard block exclusions. Only areas
deliberately registered with `mc.protectArea` receive persistent position
protection. Saving a design does not protect it. Build and repair preserve
explicitly protected areas.

For a shaft with a visible water landing, target the opening with `descend:true`
and omit `distance`. The client verifies the shaft and landing; Baritone
performs the fall. Flowing water is permitted only at that verified landing.
Dry-fall limits remain unchanged.

`allowPlace:true` enables native bridging and pillaring, with `maxPlace`
defaulting to 16. `placeBlock` selects a full solid inventory block; defaults
are dirt, cobblestone and netherrack. Results contain `route.routeId`, the
placement budget, and server-confirmed cells. After reaching safe footing,
`mc.cleanupRoute` removes reachable recorded route blocks from top to bottom
without walking or removing your footing. It returns partial or blocked with
remaining targets if more removal needs another work position. Leave those
blocks if reaching them would undo your exit. Its journal survives cancellation
and restart. Confirmed route supports remain removable and do not protect
surrounding terrain. The same rule applies to Baritone builder scaffolding
outside the saved design footprint. `mc.pillar` uses this same native movement
path with a budget equal to its height.

### Surfaces, water and swimming

Walking uses collision shapes and fractional surface heights for paths,
farmland, slabs, stairs, carpet, snow and other solid supports. An approximate
target Y snaps to a clear supported surface within 1.25 blocks. Body/head
clearance, bounded jumps and drops still constrain routes. Wooden doors and
fence gates can be opened along the route. Surface swimming and ladder routes
use native movement. Swimming stays near the surface. An active play session
keeps the player afloat between actions and turns. This uses native upward
fluid movement, with no horizontal travel or synthesized keyboard binding.
Ending the session releases control and disconnects.
Navigation can ascend from a submerged starting position; underwater
exploration is not supported. Mounted `mc.goto` steers boats on open water and
saddled horses on land. Boat destinations must be open water; use
`mc.dismount` before land travel.

### Mounts

`mc.mount` prepares adult horses, donkeys and mules automatically when needed:
ride empty-handed until tamed, dismount, use an inventory saddle, and board
again. Preparation uses ordinary survival interactions and vanilla taming
chance; it continues until successful, blocked or replaced. Set `prepare:false`
to require a ready mount. Missing saddles, babies, occupied mounts and
unsteerable llamas are refused. Completion adds `{attempts,tamed,saddled}` to
`{vehicle,confirmation}`; the booleans describe preparation performed by this
action. Nearby horse records include `mount:{tame,saddled,baby,steerable}`;
passive animals also expose `baby`. Cancellation stops preparation; any
completed taming or saddle placement remains. If still riding, use
`mc.dismount` to leave the animal.

## Mining and gathering

### Gathering

`mc.gather({item,count,near?,radius?})` delegates source search, route
selection, tool use, mining and matching-drop pickup to Baritone's native
`MineProcess`. Source discovery uses Baritone's cached world and chunk scanner,
bounded to the requested cube. The fixed inclusive cube defaults to the
starting position and radius 16 (maximum 32). Cached candidates are checked
against loaded terrain; mining and access clearance cannot leave that cube. Use
`mc.explore` (native `ExploreProcess`) before searching an unknown area; native
`GetToBlockProcess` powers `mc.findBlock`.

`count` is additional inventory gain: five logs held plus `count:4` needs nine.
Vanilla drop sources and harvest-tool compatibility include Silk Touch. Only
actual inventory increases count; crafting, smelting and crop production use
their dedicated actions. Explicitly protected areas are excluded from source
selection and access excavation. Ordinary builds, observed placements and
nearby terrain remain removable. The native mutation boundary rechecks the live
world before every mining attempt. `allowStructures:true` is rejected. This
action does not place bridges or pillars.

Gather completes only when the requested inventory gain is observed. Native
source exhaustion or failed access returns `partial` if items were gained and
`blocked` otherwise, with reason `sources_exhausted_or_unreachable`. This does
not establish which cause occurred. Gathering checks pickup space before mining
and throughout the action. If the requested item no longer fits, it returns
`inventory_full` immediately: `partial` if items were gained, `blocked`
otherwise. Free space, then retry the remaining count. Compatible stacks with
room still allow pickup, including an occupied offhand stack; an empty offhand
is not free pickup space. Other reasons include `no_mining_source`,
`missing_harvest_tool`, `player_died` and `client_disconnected`.

Results include `{id,status,reason,backend,process,phase,pos,targets,area,item,
requested,initial,count,gained,remaining,confirmation}`; `inventory_full` also
includes an `explanation` telling the caller to free space and retry. `area`
contains inclusive `min`/`max` block positions. `process` is `MineProcess`;
confirmation uses server inventory updates. Progress and the terminal action
event preserve these fields. Collection terminal results are `{status, reason,
item, requested, initial, count, gained, remaining, complete, blocker,
failures}`. Pickup adds `entityId`. Other per-drop collection codes include
`inventory_full` and `pickup_not_confirmed`. Gathering retains pickup blockers
in its terminal result. Replacing the action cancels native mining and routing,
releases controls and restores settings. Inventory gains already observed
remain real.

Mining and pickup send arrival regions to Baritone, which can excavate a route
instead of requiring an already empty standing cell. Gathering's area still
fences every block-breaking admission; an excavation outside that cube returns
`access_outside_gather_area`. Inventory gains remain in a partial result.

### Breaking blocks

`mc.breakBlocks({positions,use_hands:true})` permits breaking without a
harvest-capable tool, including access obstructions. `use_hands` defaults to
`false`: missing equipment returns `missing_harvest_tool` with a retry hint.
The override still prefers suitable equipment when available. Otherwise
breaking is slower and blocks that require tools produce no drops. It preserves
safe landing, unbreakable-block and server-confirmation checks. `mc.gather`
continues to require equipment that produces the requested item.

Mining chooses a visible reachable face before moving. It may reorder the
requested positions: exposed blocks are mined first, and obstructed positions
are retried after confirmed progress. Access clearance may remove other
breakable blocks outside explicitly protected areas. Saved plans and placement
history do not add protection. If no remaining target is reachable,
`progress.blocked` lists the remaining blocked coordinates alongside
`completed` and `remaining` counts.

`breakBlocks` returns `{reason, changed, explanation, count, removed,
alreadyClear, cleared, adaptations, confirmation}`. `already_clear` with
`changed:false` means zero blocks were removed. `blocks_removed` with
`changed:true` identifies physical progress. Both remain successful idempotent
completions; the reason is also promoted to the terminal action event. `count`
is the number of requested positions handled; `removed` counts newly confirmed
removals, `alreadyClear` counts positions already containing air or pure fluid,
and `cleared` counts access obstructions removed. Waterlogged solid blocks are
not empty. Confirmation uses the first server-confirmed replacement of the
original block type, so water, lava, or falling material arriving afterward
does not erase the receipt. Removal does not promise an item drop or a safe
place to stand.

On failure, progress retains those counts. `result.blocker` identifies `target`
(the requested position), `digTarget` (the current clearance block, if any),
`playerPosition`, `reach`, and any observed `obstruction`.
`requires_elevation`, `mining_stance_changed`, `block_not_mineable`, and
`missing_harvest_tool` explain specific blockers. Mining retries changed reach
from distinct standing positions; exhausting those approaches returns control
to the agent. Retrying the same list keeps already-cleared positions distinct
from new work.

## Collecting drops

Use `mc.collect({near:[x,y,z],radius:16})` to pick up all dropped items in a
fixed whole-block cube. Both fields are optional; the defaults are the starting
position and radius 16. Item filters and requested counts belong to
`mc.gather`, which uses native mining and matching-drop pickup. Farming sweeps a
radius-4 cube around each harvested plot, collecting produce and seeds together.

The mod discovers live item entities and checks capacity using stack
components. The controller chooses the nearest eligible drop, rechecks after
every approach, and continues past failed drops. Movement, merged entities,
partial stacks and incidental pickups are reconciled against fresh entities and
inventory. An entity disappearing alone is not counted as collected. Failed
approaches are remembered during the collection action. Replacing the action
cancels the sweep.

```json
{
  "status":"partial",
  "reason":"drops_unreachable",
  "complete":false,
  "area":{"near":[10,64,20],"radius":16},
  "collected":[{"item":"minecraft:oak_log","count":8}],
  "remaining":[{"id":123,"uuid":"...","item":"minecraft:coal",
    "pos":[12,67,20],"reason":"requires_elevation","blocker":{"aboveReach":true}}],
  "remainingCount":1,
  "omitted":0,
  "cleared":0,
  "confirmation":"inventory_changes_and_remaining_entities"
}
```

`collected` is the positive inventory change by item ID during this action.
`completed/area_clear` means no drops remain in the loaded area, including an
initially empty area. `partial` means some items were gained and some remain;
`blocked` means none were gained. The top-level reason is `inventory_full` when
all remaining drops lack capacity, otherwise `drops_unreachable`; each remaining
entity carries its specific reason and available obstruction evidence.
Remaining entities are capped at 64 with a full count and omission count.
Existing compatible stacks can accept more items even when all storage slots
are occupied.

Remaining entries identify item stacks and their locations. They omit stack
quantities because vanilla can leave client counts stale after a partial pickup
into a nearly full inventory. Collected totals use confirmed inventory changes.

### Automatic pickup access

`mc.collect` first tries a normal Baritone route. If that fails, the mod
samples supported pickup positions and checks the player's body corridor to
each. For local approaches (within 4.5 horizontal blocks and a safe descent of
at most three blocks), it can clear up to eight colliding blocks per item
entity inside the search cube. It uses mining's tool selection, safe-support
checks and server-confirmed digging, then rechecks navigation after each
removal. This can remove construction. Containers, fluids, exposed liquids,
falling material and unsafe support return a blocked result. Ordinary open
drops need no clearing.

The collect result adds `cleared` (confirmed removal count). Failed local
recovery includes `blocker.obstructions: [{block, pos, role}]`, `destination`,
`cleared` positions, the original pathfinding `cause`, and a specific recovery
`reason` and `explanation`. These describe a checked local approach, not proof
that all possible routes are blocked. A clear corridor with a failed route
returns `pickup_route_unresolved` without demolition. No supported local
approach returns `no_safe_pickup_access`; exhausted clearance returns
`pickup_clearance_limit`. Safety and reach errors are reported directly.

## Building

`mc.build` and `mc.repairBuild` submit a sparse blueprint to Baritone 1.11.3's
native builder. Baritone owns placement order, equipment, inventory moves,
excavation and movement. Construction proceeds bottom-up. Only specified block
properties constrain the design; transient door, powered, lit and occupied
state are ignored. Door and bed footprints include both halves. Use `replace`
to explicitly authorize replacing an existing solid target block.

The work area extends eight blocks horizontally and four vertically beyond the
blueprint. Its height also includes the player's starting level and the block
underfoot. Repair includes all recorded scaffolding in that height range.
Carry spare dirt, cobblestone, or netherrack for temporary access. Design
materials are reserved for blueprint cells. Temporary off-blueprint placements
are journaled before mutation and removed in a cleanup pass. Access excavation
remains; clearing a route does not require rebuilding its obstructions.
Containers and changes outside this area stop construction. Interrupted
progress and the journal survive repair; the controller renews a native lease
while the action is owned. A lost lease cancels native movement. The shared
finite-action deadline still applies.

### Schematics

Instead of `blocks`, a saved build can specify:

```json
{"planId":"roof","schematic":{"file":"roof.schem","origin":[0,70,0],
 "mirror":"x","rotation":90,"repeat":{"count":2,"offset":[8,0,0]}},
 "materials":"inventory"}
```

Files live under the player home's `schematics/` directory. Baritone supports
Sponge v1/v2 `.schem`, MCEdit `.schematic`, and Litematica v7 `.litematic`.
Mirror applies before clockwise rotation; repeated copies use nonnegative,
nonoverlapping offsets. Air cells are skipped unless `includeAir:true`. The
exact transformed block states are saved for `verifyBuild` and `repairBuild`.

### Build results

Terminal build results are delivered with the action event:

```ts
type BuildResult = {
  planId: string | null;
  backend?: "baritone"; backendVersion?: "1.11.3";
  status: "completed" | "partial" | "blocked" | "cancelled";
  reason: string; complete: boolean;
  total: number; verified: number; remaining: number;
  changed: { placed: number; cleared: number };
  accessCleared?: { count: number; targets: number[][] };
  blockers: Array<{
    target: [number, number, number]; reason: string; explanation: string;
    actual?: string; obstruction?: [number, number, number];
  }>;
  omittedBlockers?: number;
  missing: Record<string, number>;
  cleanup: { remaining: number; targets: number[][] };
  workArea?: { min: number[]; max: number[] };
  timingsMs?: { total: number };
};
```

`completed` requires actual world verification of the blueprint and cleanup.
`partial` means some cells are verified but work remains. `blocked` means none
are verified. Cancellation retains the last readable world assessment. Counts
refer to requested cells; `changed` includes temporary changes this invocation.
Results retain eight example blockers and cleanup targets, with remaining
counts, and are saved under `build-results/<jobId>.json` before publication.
`timingsMs:{total}` covers the native construction and cleanup action.

Blocker codes include `target_occupied`, `invalid_block_state`,
`unsupported_build_block`, `terrain_unloaded`, `unbreakable_obstruction`,
`container_contents_unknown`, `plan_footprint_conflict`, `missing_materials`,
`placement_unavailable`, `builder_paused`, `builder_stopped`,
`cleanup_pending`, `build_work_area_exceeded`, `build_repeated_changes`,
`build_journal_unavailable`, `build_volume_too_large`, `unsafe_support_removal`,
`player_died`, `screen_open`, `client_session_changed`, and
`build_lease_expired`. `builder_paused` reports an upstream refusal without
claiming a proven cause. Resolve the reported issue and call
`mc.repairBuild({planId})`; the same immutable blueprint is compared against
the current world, including work already done.

### Placements

All placements remain removable by default, including access supports, torches
and ordinary construction. Both a replaceable target cell and a solid support
face use normal placement behavior. To preserve a particular area at the
request of the person you play with, register its exact bounds with
`mc.protectArea`.

If the player's body blocks placement, `mc.useItem` returns
`placement_blocked_by_player` without repositioning the player. Its blocker
includes `suggestedPlacement` when current world geometry offers a usable
choice. Move to `standingPosition` with the supplied `standingDistance`, then
retry using `position`. Suggestions do not modify the saved plan and do not
claim a route was found. Revalidate through the ordinary action after moving.

```ts
type PlacementSuggestion = {
  position: [number, number, number];
  block: string;
  standingPosition: [number, number, number];
  standingDistance: number;
  validation: "current_world_placement_geometry";
  routeChecked: false;
};
```

## Items, crafting and containers

### Using items

For `mc.useItem`, a block item aimed at an empty/replaceable cell is placed at
that cell using the same support-face selection as the builder. A solid target
retains ordinary use-on-block behavior (`face` chooses its clicked face),
except a block item against a container uses sneak-placement instead of opening
it. Use `mc.openContainer` to open a container. This lets an agent place a
table at an empty coordinate without manually computing the supporting block
below it. Named multi-block construction still uses `mc.build`. Item use equips
directly from any inventory slot; an explicit hotbar move is unnecessary.

Food and drinks run through one consumption, regardless of `holdMs`. That
duration only controls other held items such as shields and bows. Food/drink
returns `reason:"consumed"` after confirmed consumption;
`consumption_interrupted` or `consumption_unconfirmed` returns
`status:"blocked"` with a plain explanation when consumption is not confirmed.

Boat item use treats a requested water block as a water column, checks up to 16
blocks upward for its surface, chooses a ray hit where the full boat fits, and
approaches from a suitable bank. An obstructed or too-narrow target remains a
blocked action.

Item-use completion is
`{status,reason,explanation?,item,confirmation,changedBlocks,released,inventoryDelta}`;
the delta maps item IDs to signed count changes. Current inventory arrives in
the accompanying world state and remains available through `mc.inventory`.
Raw native diagnostics retain before/after inventories. `mc.closeScreen`
returns `{ready,screen,confirmation}` rather than another full world snapshot.

### Crafting

Crafting uses the server's recipes: the player grid is 2×2 and a table is 3×3.
Request the final output, for example one wooden pickaxe from three logs. The
controller plans craftable intermediate ingredients from current inventory,
then executes unlocked recipes with normal survival transactions. It never
mines or smelts missing raw supplies. `prepareIngredients:false` limits crafting
to the requested recipe. Counts request additional output, independent of items
already held.

Request the full crafting count in one call. For example,
`mc.craft({item:"minecraft:bread",count:18})` makes 18 additional loaves;
`mc.craft({item:"minecraft:oak_planks",count:5})` makes 8 planks because each
recipe produces 4. The mod fills the recipe book's grid, takes only the
requested recipe batches, confirms server inventory updates and returns unused
ingredients. It does not shift-click an unlimited amount of output. A completed
result includes `{requested,crafted,recipe,confirmation:"server_inventory_update",
batch:{elapsedMs,transactions,outputTransfers}}`. Replacing the action stops
further batches and returns grid ingredients; already crafted items remain real.

`mc.craft` searches for an already placed table within 16 blocks before
crafting; pass `table` to choose one. Place one explicitly with `mc.build`
first if needed. A missing bench returns `crafting_table_required` before
consuming ingredients. Completed intermediates remain after interruption.
Planning is bounded; a recipe cycle, unsupported remainder chain or excessive
search returns a blocker. Dynamic outputs outside the recipe book (such as item
repair or copying a filled map) are unsupported.

The initial supply plan runs before consumption. Each preparation step
forecasts the vanilla recipe matcher's actual material choices and retains a
feasible plan for the requested item; confirmed inventory is read again after
every step. Named, enchanted and damaged stacks are excluded from preparation
supplies. This is a sequence of confirmed transactions, not an atomic batch:
inventory or world changes can leave partial preparation, which the
result/progress preserves.

Crafting rounds up to a whole recipe batch, collects the result into player
inventory, closes the screen, and reports
`{item,requested,crafted,destination:"inventory",recipe,confirmation,table?,preparation?}`.
`preparation` lists intermediate `{item,crafted}` batches. Automatic selection
of an existing table reports `table:{pos:[x,y,z]}`.

### Recipe books

Crafting uses the server's recipes and vanilla recipe-book requests for both
the 2x2 inventory and 3x3 crafting table. Ingredient preparation waits briefly
for server recipe-unlock updates. A `recipe_book_entry_missing` blocker names
the missing entries and has `retryable:false`; it does not assert a server
crafting ban. The server also requires unlocked entries for recipe-book
requests, even with `doLimitedCrafting=false`. There is no manual-grid
fallback. A server operator must repair inconsistent saved recipe/advancement
state; an ordinary client cannot grant itself recipes.

### Containers and inventory

Block reads include `canOpenContainer:boolean`, derived from the current
block's screen-handler capability. Container actions reject a stale
air/non-container target before navigation; smelting also requires a furnace,
smoker or blast furnace.

Chest, barrel, shulker, hopper and dispenser slots use `storage`. Furnaces,
smokers and blast furnaces expose `input`, `fuel` and `output`. `mc.transfer`
opens `target` when supplied; otherwise it uses the current container. Taking an
output slot requires its complete stack. Close the screen before movement. Reads
and chat continue while a container is open.

Counts are exact for moves, transfers and drops. Output space and supplies are
checked before effects. Operations use vanilla survival slot packets;
completion requires server inventory updates. A changed container, stale state,
full destination or missing supply blocks the operation. Inspect partial
progress and current inventory after an uncertain result before issuing another
request. Dropping confirms the item left inventory, not delivery to a player.

Inventory slots 0–8 are the hotbar, 9–35 storage, 36 boots, 37 leggings,
38 chestplate, 39 helmet, and 40 offhand. Armor and offhand operations require
the player inventory handler, so close a container first. Incompatible
equipment and replacing a different item with `moveItem` are rejected; use
`swapSlots` to exchange stacks.

`mc.smelt` requires empty input/output and enough of the specified fuel for the
batch. It supports furnace, smoker and blast-furnace recipes. With `wait:false`,
it returns after loading; use `mc.container` and withdraw from `output` later.
Replacing or cancelling the action leaves the furnace cooking normally. Craft
accepts up to 256 requested outputs, smelting up to 64 inputs, and
transfers/drops up to 2304 items per call.

## Farming

`mc.farm` tends 1–64 explicit soil blocks. Supported seeds are
`minecraft:wheat_seeds`, `minecraft:carrot`, `minecraft:potato`, and
`minecraft:beetroot_seeds`. It uses an inventory hoe where soil needs tilling,
plants empty plots, and leaves existing immature crops growing. `boneMeal`
allows up to that many uses per plot; zero is the default. Each mature crop is
harvested at most once, its drops collected, and the plot replanted. Disable
`harvest`, `plant` or `collect` explicitly when that step is unwanted.

The routine creates no water or supplies. Preflight rejects occupied plots,
unsupported soil, and insufficient planting/replanting seeds. It preserves
other crops and blocks. Replacing the action stops the current primitive;
already completed world changes remain. Repeat against current world state.

Completion includes `{tended,harvested,planted,boneMealUsed,growing,plots,
inventoryDelta,confirmation}`. `planted` counts planting operations, so a new
plot grown, harvested and replanted contributes two. `plots` contains each
`{soil,crop,age}` after tending. A growing crop is useful partial progress,
not a claimed harvest. Actual inventory changes and server block updates are
the evidence; natural growth and bone-meal effects remain vanilla Minecraft.

## Combat and defense

### Attacking

`mc.attack` tracks the observed entity ID and UUID. The mod aims and performs
cooldown-ready melee strikes on game ticks, and raises an available shield
between strikes. If the shield starts in inventory, vanilla slot swapping moves
it to offhand and preserves the previous offhand item in its former slot. The
shield stays equipped afterward; cancelling or replacing the action releases
its use and stops pursuit. The existing action and native leases fence stale
work. The controller handles pathfinding toward distant or occluded targets.

Completion returns `{outcome:"dead"|"gone",strikes,lastHealth,shieldEquipped}`.
Only `dead` confirms a kill. The read-only native diagnostic observation
includes `combat:{id,entityId,uuid,status,reason,strikes,outcome,lastHealth,
needsApproach,shieldEquipped,guarding}` and `usingItem`; these are internal
controller/eval state, not extra tools the player must manage.

### Several enemies

```sh
musecraft attack entityId=42       # replace current work; start fighting now
musecraft queueAttack entityId=43  # append without restarting target 42
```

Physical jobs continue after their result and the agent's turn. The player is
woken by the events listed under
[Waiting, standby and wake-ups](#waiting-standby-and-wake-ups), including the
owner's chat, danger and an action outcome.

`attack` clears the old queue. `queueAttack` appends a live observed entity,
bound to its UUID, or starts an attack when idle. Duplicate targets are
ignored. A different active action returns `attack_not_running` without
replacing it; 32 live targets including the current target returns
`attack_queue_full`. Unavailable targets return `target_unavailable`. A target
finishing during an append can return `attack_not_running`; use fresh state to
choose the next attack. The mod advances through targets without waiting for a
model turn.

Current action progress includes `entityId`, `uuid`, `queue:[{entityId,uuid}]`
and `outcomes:[{entityId,uuid,outcome,strikes,startedAt,firstStrikeAt,finishedAt}]`.
The final result is `completed/targets_defeated` only when all targets were
observed dead. Missing or reused identities produce `partial/targets_unconfirmed`,
preserving per-target evidence. The latest 32 outcomes are retained with
`omittedOutcomes`. Blocked navigation reports its cause and leaves the remaining
queue in progress evidence. Queue completion sends one action outcome event,
not one per strike or per target. A new physical action cancels combat and its
remaining queue.

### Fleeing

`mc.flee({entityIds:[firstId,secondId],distance:40})` binds each live entity
UUID. Calling `mc.flee` again adds threats to the existing action; an invalid
addition leaves that action running. It continuously maintains horizontal
separation from every tracked threat. Progress and phase events include their
IDs, UUIDs, last observed positions, loaded status and distances. The mod gives
Baritone short run-away goals, revalidating moving targets without cancelling a
valid path. It disables parkour, block breaking/placement and falls over one
block, and adds avoidance costs for nearby hostiles. Terrain and movement
remain subject to normal game physics; this is not a guarantee of safety against
every mob or projectile.

At the requested distance it holds; four blocks of renewed approach resumes
retreat. It continues until replaced, all tracked entities are observed dead,
or a blocker occurs. An unloaded threat retains its last observed position;
losing sight does not clear the threat. `escape_route_unavailable` returns
Baritone's inability to find an escape in the current region. Flying and
mounted clients must land/dismount first. Action replacement, disconnect and
the renewable movement lease release controls and restore normal navigation
settings.

Meaningful phase changes emit:

```json
{"kind":"flee_state","phase":"holding","entityId":42,
 "entityUuid":"...","distance":40.2,"safeDistance":40,"revision":1}
```

`retreating` is emitted when danger closes again. These are ordinary mailbox
events; per-frame position and distance changes do not wake the agent.

### Threats

`threat_escalated` is urgent, as is the first hit in a damage burst. Any open
`musecraft` command returns with it at once, and standby wakes an idle player;
the physical action continues until the player replaces it.

```json
{"kind":"threat_escalated","targetingPlayer":"unconfirmed","health":20,
 "entities":[{"id":42,"uuid":"...","distance":9.5,
   "threat":{"level":2,"severity":"danger",
     "evidence":["moving_toward_player","dangerous_proximity"],
     "targetingPlayer":"unconfirmed","pursuitInferred":true}}]}
```

Visible threats escalate for proximity within ten blocks, sustained movement
toward the player within sixteen, or an attack-state flag within twenty-four.
Within four blocks or during a creeper fuse, severity becomes `immediate` (3).
The client does not receive a general authoritative mob target: proximity and
pursuit are risk evidence, not confirmed aggro. Damage keeps its existing
observed attacker signal. Stable danger produces no repeat; a higher level
alerts again, and ten seconds of lower danger rearms the previous level.

The controller requests one urgent decision when damage or an escalation first
needs attention. While it is pending, further damage and threat sightings update
the current state and command results (`threatUpdate`) without queuing more
events. Ten seconds without visible danger or damage clears the encounter and
its damage totals; the next danger requests a new decision. Delivery
acknowledgements and controller restarts preserve the pending decision. Death,
respawn and explicit stop keep their separate lifecycle fences.

### Defense priority

An active attack cannot be replaced by ordinary actions. An active escape has
the same protection until the controller has observed ten seconds clear of
threats and damage. A blocked replacement returns
`{ok:false,status:"blocked",reason:"defense_in_progress",defense:{action,status}}`
and leaves the running job and action sequence unchanged. `attack`,
`queueAttack` and `flee` remain available to change the defense, and
`musecraft wait` waits for it. Death permits `respawn`, and `musecraft stop`
still stops play. Reads and chat remain available during defense.

## Reading the world

### State

Command output and `musecraft state` render a deterministic brief of the
current state: quoted game names, timestamps, readiness, player condition and
inventory, the current action and last result, nearby entities and blocks, and
an open container. Inventory preserves occupied player slots (up to 41);
containers show up to 24 occupied slots with omission counts. Nearby blocks
retain up to 12 type representatives. Entities show up to eight: event
attackers, players, then visible or close entities. Entity IDs, position,
visibility, health, dropped items and mount details remain available;
`mc.entities` reads the full native sample. Action results have bounded depth,
field and array counts with explicit truncation markers. A disconnected,
unknown-age or over-five-second-old snapshot is marked stale. Game text is
escaped as data.

`world.nearbyBlocks` includes `atFeet`, `belowFeet` and `blocks`, the nearest
representative of up to 12 different non-air block IDs in a radius-4 cube. Each
representative has `name`, `pos`, `properties` and its matching cell `count`.
`omittedTypes`, `truncated` and `unloadedCells` expose the sample limits. This
is a loaded-world scan, including blocks behind walls, not a visibility claim.
Use `mc.scanBlocks` for individual positions; repeated block types are grouped
only in the automatic summary. Scans never move the player or replace an action.

Each state includes
`droppedItems:{items:[{id,uuid,name,count,pos,distance,visible}],radius:32,omitted}`.
It contains the nearest 32 live drops regardless of line of sight, separate from
the eight other selected entities. `onlinePlayers` contains
`{players:[{uuid,name}],count,omitted}`, showing at most 32 entries. Ordinary
changes remain quiet.

### Long-range visibility scans

`mc.scanBlocks({name:"minecraft:hay_block",radius:128})` searches loaded
terrain in every direction without turning the player or replacing an action.
`mc.scanBlocks({names:["#minecraft:logs","minecraft:stone"],radius:128})`
filters the same scan by several types with one shared limit. Results are
`{center,origin,radius,visibility,blocks,complete,omitted,truncated,
unloadedChunks,stopReason,stats}`; omit the name to list non-air blocks.

Range is measured from the captured eye position to the target outline;
returned blocks are sorted by minimum distance to their block cells. A
chunk-section palette rejects sections without matching states before
inspecting individual blocks. Block outline rays test target centers and inset
face corners; opaque walls, closed doors and other intervening outlines
obstruct the scan. Transparent blocks also obstruct these conservative rays;
this is not pixel-perfect rendering. Very narrow exposed slivers can be missed.
Fluids do not obstruct rays.

The mod spreads work across game ticks with a 2 ms target slice and a 4 second
wall-clock limit. `stats` reports sections, candidate/ray counts, slices, CPU
time, maximum slice time and elapsed time. `complete:false` means the result was
limited by unloaded chunks, the result limit, or the work budget. `stopReason`
is `exhausted`, `result_limit` or `work_budget`. `omitted` is zero for a
complete scan and null when the unexamined or unloaded area makes the count
unknown. `truncated` mirrors `!complete`. Empty incomplete results do not
establish absence. Narrow the selector or change viewpoint and scan again.
Concurrent scans return `scan_in_progress`; disconnect/world replacement fails
the scan.

### Local block searches

`mc.findBlocks({names:["minecraft:chest","#minecraft:logs"],radius:16})`
searches once for several materials in the loaded world, including hidden
blocks. Names accept block IDs or registry tags/categories such as `bed`,
`beds`, `#minecraft:beds`; unknown names fail explicitly. Each filter returns
`blocks`, `total`, and `omitted`; `unloadedCells` describes unknown coverage. A
shared `limit` (default 64) is divided among filters. The automatic radius-4
summary and `mc.findBlocks` are local loaded-block queries; they do not use
long-range rays.

### Region inspection

`mc.inspectRegion({min:[0,60,0],max:[15,80,15],limit:64})` returns material
counts/bounds and exact non-air blocks with properties from a retained scan.
An optional `names` array filters matching blocks. Bounds are inclusive, at most
64 per axis (`max <= min + 63`) and 65,536 cells. `scanId` plus `nextOffset`
reads another page: `mc.inspectRegion({scanId:"returned-id",offset:64,limit:64})`.
Eight scans are retained in memory; reconnecting to another world or restarting
the client invalidates them. Counts and pages describe the captured scan, not
live edits afterward. Check `changedDuringScan`, `unloadedCells`, and
`omittedMaterialTypes` before treating the scan as a complete structure.

Air is omitted by default. Explicit `names` filters can request
`minecraft:air`, `minecraft:cave_air`, or both, including their coordinates and
material counts. `airCells` counts all air in the box independently of the
filter. Unloaded cells remain unknown and are never returned as air.

### Log context

`scanBlocks`, `findBlocks`, and the automatic nearby-block summary attach this
optional context to log blocks:

```json
{
  "kind": "likely_structure",
  "basis": "local_block_patterns",
  "evidence": ["nearby_built_blocks"],
  "nearbyBuiltBlocks": ["minecraft:oak_door", "minecraft:oak_planks"],
  "naturalLeafCount": 0,
  "persistentLeafCount": 0,
  "connectedLogs": 6,
  "partial": false
}
```

`likely_tree` requires soil at the connected log base, nonpersistent leaves, no
nearby construction cues, and a complete bounded inspection. Construction cues
take precedence; trees beside a building can therefore be classified
conservatively. Bare logs, unloaded chunks and oversized components can remain
`unknown`. These are local estimates, not proof of who placed a block. Scans
inspect at most 64 connected logs, reuse results for up to 20 world ticks, and
invalidate them when server block updates arrive.

### Screenshots

`musecraft screenshot` captures the game without moving and returns a PNG path;
read that file to see the image. Screenshots help recognize structures and
assess appearance. Use spatial queries for exact coordinates and material
quantities.

## Events

The events that wake the player are listed under
[Waiting, standby and wake-ups](#waiting-standby-and-wake-ups); the others
arrive with the next output. The controller keeps current state internally.
Ordinary state changes stay quiet.

Continuous native frames replace the latest world state. Routine changes wait
at least 10 seconds and do not interrupt a running action. Chat, damage,
visible nearby threats, action completion and blockers remain urgent (200 ms
batching). Damage includes the server-observed attacker when available. Entity
views include dropped item stacks; player views include vehicle and sleeping
state. A routine frame is never an instruction to cancel useful work.

Event batches remain offered until acknowledged. Pending world snapshots
coalesce; ordered chat is preserved. Queue limits cause backpressure or explicit
retention-gap events, never silent claims of complete history.

### Readiness

Initial connection setup waits through the native `joining:true`
terrain-loading phase before delivering the first event. Once play has started,
connection changes still reach the same conversation. This does not suppress
ordinary container screens or replace the session manager's reconnect and
respawn logic. A play session emits one `ready` event when its first ready frame
arrives, so a restored conversation resumes immediately. Subsequent quiet frames
do not wake the player; reconnects emit `ready_again`. Both readiness events
persist until acknowledged.

### Player presence

Server player-list packets produce normal-priority `player_joined` and
`player_left` events, including players outside render distance:

```js
{kind:"player_joined", player:{uuid:"...",name:"..."}, observedAt:"..."}
{kind:"player_left", player:{uuid:"...",name:"..."}, observedAt:"..."}
```

Each event uses the existing durable batch/ACK path and includes the current
state envelope. The player's own connection changes use the readiness events.
Initial roster synchronization, dimension changes, latency/display-name updates
and duplicate packets do not produce presence events. Controller restarts
retain the cursor and unacknowledged batch; native history retains 128
transitions and reports a retention gap if overrun. A new play owner adopts the
current roster without replaying old transitions. Presence reflects the roster
the server exposes to this client. Servers that hide players or synthesize
tab-list entries can change that visibility.

### Respawn

When it observes death, the controller emits one `died` event with the same
`death` record. It then emits one `respawned` event when the
player is alive, terrain is loaded and the client is ready to act. It wakes the
same conversation even when no action remains. The pending death and event
delivery survive controller restart. Repeated ready frames and ordinary menu
transitions stay quiet; a new play-session generation discards the previous
pending death.

```js
{kind:"respawned", death:{observedAt, pos, dimension},
 id, sequence, observedAt, clientEpoch}
```

`death` describes the observed death location. The accompanying current-state
brief contains the respawn location, health, inventory and last action result.
The agent chooses its next action; respawning does not replay interrupted work.

### Morning

The controller sends one normal-priority `morning` event per newly observed
Overworld day. This includes nights skipped by sleeping. Delivery waits until
the player is alive, awake and ready. It does not wake the player; it arrives
with the next output.

```js
{kind:"morning", day, timeOfDay, id, sequence, observedAt, clientEpoch}
```

The mod's native observation adds `worldDay`, the zero-based absolute day
(`floor(world time / 24000)`); `timeOfDay` remains the clock within that day.
The controller persists `{server, day, pendingDay}` as `morningCursor` in its
event inbox. Pending sleep transitions and unacknowledged events survive
controller restart. Repeated ticks, reconnects and backwards clock adjustments
do not repeat an announced day. Joining adopts the current day without an extra
morning; missed days coalesce to the current day, and an obsolete morning is
dropped once night begins.

Combat events (`threat_escalated`, `flee_state`) are described under
[Combat and defense](#combat-and-defense).

## Resource and build protection

`mc.protectArea({min:[x,y,z],max:[x,y,z],label:"home"})` adds a durable,
inclusive box in the current server and dimension. Maximum size is 256 by 384
by 256 blocks. Use it only when the person you play with explicitly asks to
protect that area. Include its intended footprint and foundations. It changes
protection metadata only. There is no gathering override or model-facing
removal operation.

Block reads expose `resource:{allowed,basis?,reason?,explanation?}`.
`allowed:true` means the loaded position is outside explicitly protected areas;
it does not promise that mining it is safe or that it contains natural terrain.
Ordinary builds, saved blueprints and observed placements do not acquire
protection. Protection stops at the registered bounds without a surrounding
exclusion radius. Old automatic placement records are retained in the metadata
for rollback but no longer impose protection.

The same area boundary applies to direct mining, gathering, route excavation,
pickup clearance, placement, build/repair and scaffold/route cleanup. A
blueprint or cleanup journal cannot bypass an explicitly protected area.
Block-type exclusions, container-content checks and fall-safety checks remain
in effect. Corrupt or unwritable protection metadata blocks mutations rather
than dropping protection. Protection files live under the player home's
`protected-worlds/`.

## Operator diagnostics

The player does not need these commands. They print raw controller JSON.

```text
musecraft start
musecraft observe
musecraft session status
musecraft session retry
musecraft avatar status | load PATH | upload | reload
```

`start` starts the controller service and prints its status; every other
command starts it quietly when needed. `observe` prints the latest world
snapshot of the active play session. `session status` prints the play session,
including its `id`, owner, status and last error; `session retry` clears a
blocked session after the cause is fixed. `avatar` drives Figura in the running
client: `status`, `load` a local avatar folder, `upload` it for the signed-in
account (with the owner's approval), or `reload` avatars.
