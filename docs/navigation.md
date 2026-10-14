# Navigation planning

`mc.goto({target: [x,y,z]})` resolves a fixed place.
Coordinate walking arrival means safe footing inside the spatial radius, including
across obstacles. Use a small radius to enter a specific room; high-level
interaction tools select their own usable work positions and require visibility.
`mc.goto({target: entityId, distance: 2.5})` tracks an entity natively each tick.
The action pins its UUID, retargets routes as it moves, and completes only at a
valid standing position within the live distance and line of sight. Losing that
entity blocks the action; it never completes at the last known coordinates.
A search already in progress finishes before retargeting so movement cannot
continually discard the search. Replacing the action releases native movement.
Entity navigation requires dismounting a boat; fixed water routes are unchanged.

## Ground movement: Baritone 1.11.3

Musecraft embeds the unmodified, checksummed Baritone 1.11.3 release for
Minecraft 1.21.1. `BaritoneNavigation` registers one action-owned process through the public
API. Baritone searches on its worker threads and executes continuous native
movement, including connected route segments, doors, stairs, ladders and water.
`SpecializedNavigation` retains explicit creative flight and mounted travel;
ordinary walking never falls back to the old planner.

`BaritoneGoal` snapshots acceptable action work positions on the client thread.
Coordinate goto uses `BaritoneDestination`: destination cell volumes intersect
the requested arrival region, including integer corners and partial-block
feet heights. Candidate cells do not require current visibility, empty body
space or existing support; Baritone owns route
feasibility and permitted excavation. Its native enclosing-goal heuristic is
constant-time. Cells centered inside the region are tried first; failed search
or exhausted alignment choices expand to intersecting boundary cells. Tight
corner targets use boundary cells directly. After arrival, the client checks the exact radius and live
geometry, aligns within the cell, or excludes that cell and replans. This
prevents a block-center discretization from rejecting a sub-block target. Its
immutable goal is safe for worker search: goal evaluation never reads live
Minecraft objects off-thread. One search can reach any valid work position;
arrival is checked again against live distance and collision; action and entity
goals also check reach, visibility and identity. A short within-cell alignment
handles precise callers. Final arrival
requires live goal acceptance, a safely interruptible Baritone movement, and
three settled ticks (surface swimming permits vertical bobbing). If a pickup removes
the target during a gap jump, movement reaches a safe landing before returning
that disappearance for inventory confirmation. Explicit replacement still
cancels the old action immediately.

Block changes around action destinations rebuild their candidate positions.
Coordinate arrival regions stay fixed while Baritone observes the changed terrain;
the client invalidates final-alignment geometry. Failed coordinate endpoints
remain excluded for the duration of the action, so digging cannot resurrect them.
A valid live arrival still completes if changed geometry makes a previously
rejected cell usable.
Entity goals retain the native action identity and retarget as the entity moves.
Combat/follow polling retains its in-progress navigation instead of cancelling
and starting a new search every 500 ms. Cancellation revokes the Baritone
process, outstanding search and forced inputs before another action takes over.

Coordinate goto permits Baritone's block breaking, tool selection and inventory
access for the duration of that action. These settings reset at termination;
entity, build, mining and flee approaches retain their existing permissions.
Placement, parkour placement, water-bucket falls and chat-command control stay
disabled. Build-owned routes
permit clearing incidental leaf blocks, excluding every block type present in
the build plan. Build clearance permissions reset on each request. If all work positions are leaf-occupied, build permits one clearance
approach on real solid footing, then recomputes the actual placement predicate
from the changed world. It returns a blocker if the cleared position still does
not provide access. Mining first seeks a non-destructive route to the actual source, then clears permitted obstructions
through Musecraft's verified digging. Building retains its material reservations,
owned-scaffold journal and server-confirmed placement/cleanup. Returnable build
access rejects route segments with drops exceeding one block.

Ground `movement` and completed `goto` results expose:

```ts
{
  backend: "baritone", backendVersion: "1.11.3",
  phase: "goals" | "pathing" | "clearing" | "aligning" | "settling" | "terminal",
  requestedTarget: [number, number, number],
  target: [number, number, number], // current target for an entity
  resolvedTarget: [number, number, number] | null,
  pos: [number, number, number],
  goalSatisfied: boolean, reason: string | null,
  replans: number, retargets: number,
  firstMotionMs: number | null,
  planning: {backend: "baritone", goalWorkMs: number,
             searchWallMs: number, goalCells: number, candidateCells: number,
             searchAttempted: boolean, rejectedDestinations: number},
  maxTickWorkMs: number,
  lastPathEvent: string | null
}
```

`firstMotionMs` measures native admission to physical movement. Public tool
admission adds controller overhead, which is not included here.
`goalWorkMs` is client-thread endpoint sampling time; `searchWallMs` is elapsed
search-event time, not CPU usage. `maxTickWorkMs` covers Musecraft's navigation
callback, not every Baritone/client callback. Flight/mount diagnostics retain the
older planner fields. These diagnostics do not change gameplay tool arguments.

Default goto resolves a place within 2.5 blocks horizontally and 1.25 vertically,
using the selected floor height. An air coordinate can project
downward through a clear column. Mining, placement and pickup use their actual
action predicates; exact centering remains internal to precise callers. A
blocked route does not end the play session or establish global impossibility.

Baritone 1.11.3 is bundled unmodified; see
[dependency provenance](../mod/libs/README.md).

## Explicit distance and access diagnostics

Coordinate `goto` forwards `distance` into both landing resolution and the
native near goal. Landing search covers that horizontal and vertical radius,
including destinations whose coordinates lie inside raised terrain. An explicit
0.25–8 block walking radius uses 3D distance and safe footing; it can finish at the current
standing position. Planned endpoints sit 0.1 blocks inside the requested radius;
actual arrival checks the public radius so physics settling does not cause retries.
Default near-goal projection stays unchanged. A coordinate radius can include
positions across a wall or floor; entity and interaction goals additionally
require their live visibility/reach predicates.

Walking places no access blocks. A blocked native snapshot includes:

```ts
blocker?: {
  reason: "requires_elevation" | "descent_unreachable" |
    "search_incomplete" | "no_route_in_region" |
    "movement_stalled" | "no_acceptable_destination" |
    "destination_not_standable" | "destination_alignment_unavailable";
  cause: string; // original planner/executor reason
  position: [number, number, number];
  target: [number, number, number];
  goal: string;
  heightDifference: number;
  minimumRise?: number; // lower bound, not a verified pillar plan
  suggestedTool?: "mc.pillar";
  nearestStandingPosition?: [number, number, number];
  arrivalRadius?: number;
  explanation: string;
}
```

The adapter preserves the original error for internal mining/build recovery.
Terminal public jobs use `blocker.reason` and retain `blocker.cause`. Collect and
gather return their partial inventory progress with the same access evidence.
Search exhaustion retains `search_incomplete`; it cannot establish that a pillar
is needed. Explicit `mc.pillar` and owned build scaffolding remain separate.

Mining also measures the source against the actual eye height and block reach.
An above-reach source reports `requires_elevation` with `minimumRise` and
`suggestedTool`, preserving an incomplete search in `cause` when applicable.

Baritone may jump across ordinary gaps and excavate coordinate goto routes.
It cannot place blocks; explicit pillaring and build scaffolding own placement.
The thin-column `narrow` fixture remains a known limitation of its block-center
route graph; refusal is reported to the agent rather than retried indefinitely.

## Item geometry and local excavation

Item-use approaches retain the requested item and face. Solid faces, source
fluids and boat clearance share one resolver between candidate stances and the
actual click. Replaceable block-item targets use the builder's placement
predicate, including the player's body. Generic visibility alone is not arrival.

For mining with a reachable, removable obstruction inside its authorized area,
`miningAccess.localPathCost` estimates two removals with the best available tool
plus direct walking and a margin. The controller passes that estimate as internal
`moveStart.maxPathCost`. Baritone reports its cached movement cost in ticks; at a
safe cancellation point an over-budget route returns
`local_excavation_cheaper`. Mining then clears the observed obstruction and
recomputes. This is a heuristic comparison, not proof that a complete tunnel
exists. It adds no time deadline and does not enable block breaking for goto.
When a ray's next block is unreachable, mining checks up to four local approach
steps and their body/headroom clearance. A visible removable obstruction can be
cleared; otherwise a verified short approach returns `miningAccess.approach` so
mining can advance and inspect again. Repeated approaches are rejected.
Unbreakable or unsafe access still returns a blocker.

The internal movement snapshot includes `plannedPathCost` and `maxPathCost` for
these approaches. Gameplay tool arguments are unchanged.

## Physical failure evidence

A blocked navigation result includes `blocker.reason`, `blocker.explanation`,
`blocker.target` and `blocker.position`. `blocker.localTerrain` reports four
adjacent block centers: body obstructions and overhead collision blocks (names
and coordinates), plus the first floor surface/hazard/fluid/unloaded cell within
16 blocks below. Its `summary` is plain English. These bounded observations do
not claim to identify the exact edge rejected by Baritone or prove no route exists.

When mining refuses to remove the player's support, `unsafe_support_removal`
takes precedence over a failed alternate route. Its explanation states the
requested block, current-footing risk and possible next approach. A known dry
landing adds `fallDistance`, `landingSupport`, `landingFeetY`, `safeDropLimit: 3`
and `landingStatus: "surface"`. Hazard, fluid and incomplete terrain checks are
reported separately, without inventing a landing height. `cause` retains the
alternate navigation failure. Safe drops of at most three blocks still work;
no movement or mining safety policy is relaxed by these diagnostics.

The terminal action event carries this blocker through the same public tools.
A later state envelope retains the blocker before optional navigation metrics.

## Coordinate destination regressions

`precise_corner` covers an integer corner and two fractional points with a
0.25-block arrival radius. `portal_height` covers a descending approach to a
point in air above the real floor. `floating_precise` preserves the strict
radius and returns `destination_not_standable` with observed nearby footing.
`goto_excavation` requires Baritone to use an inventory pickaxe to clear a
sealed corridor; `goto_buried_destination` also requires clearing the destination
itself. `unbreakable_destination` checks actual failed-search evidence.
`goto_excavated_region` reaches an interior place through a solid volume and
checks that excavation does not resurrect rejected destination cells.
`boundary_arrival` requires the expanded region and collision-safe alignment
when all cell centers inside the radius are obstructed.
Each case uses public goto calls with no corrective waypoints.

## Execution progress and stable clearance

`NavigationExecution` tracks new physical positions, the highest actual vanilla
mining progress for each obstruction, and server-confirmed removal. Its state
belongs to the action and survives Baritone replanning. Fluid height oscillation,
restarting the same break and changing the path index do not count as progress.
A productive slow break can continue for its full duration; there is no overall
navigation deadline.

After three lost mining-progress attempts or 100 client ticks without new
progress, navigation inspects the executing
step's body/headroom blocks. If a reachable obstruction is removable under the
same action permissions, it releases Baritone and enters `phase: "clearing"`.
Musecraft selects a tool, mines one fixed block while refreshing its visible
face, waits for server confirmation, clears any remaining reachable headroom for
that same step, then returns the original goal to Baritone.
Area bounds, protected blocks, safe support and harvest-tool requirements remain
in force. Mining's source block stays owned by the mining action. Recovery never
places blocks. Replacement cancels both navigation and its clearance mining.

If no permitted clearance exists, or the single stable attempt fails, the action
returns `movement_stalled`. The existing terminal-action event wakes the player
agent; gather/collect retain partial progress. The agent can change the approach,
remove a reported obstacle or explicitly pillar, then retry.

Additional diagnostic fields:

```ts
movement.execution: {
  from?: [number, number, number];
  to?: [number, number, number];
  pathIndex?: number; pathLength?: number; stepCostTicks?: number;
  noProgressTicks: number; recoveryAttempts: number; clearedObstructions: number;
  clearing?: [number, number, number];
  breaking?: {position: [number, number, number]; progress: number};
}
blocker: {
  reason: "movement_stalled";
  attemptedStep: {from?: number[]; to?: number[]; pathIndex?: number};
  obstructions: {position: number[]; block: string;
                clearancePermitted: boolean; reachable: boolean}[];
  entities: {id: number; type: string; pos: number[]}[];
  noProgressTicks: number; recoveryAttempts: number; clearedObstructions: number;
  recoveryFailure?: string;
  explanation: string;
  // Existing target, position, cause and localTerrain remain available.
}
```
