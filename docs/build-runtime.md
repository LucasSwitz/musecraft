# Construction runtime

`mc.build` saves an immutable design and runs construction. `mc.repairBuild`
re-reads the same design and current world after supplies, access, or other
blockers change. Neither action has a total-duration deadline.

## Ownership and data flow

```text
mc.build / mc.repairBuild
  -> saved plan + existing action cancellation ownership
  -> native assessBuild (128 cells, tick-sliced geometry)
  -> controller ready frontier
       -> clear expected obstruction / create temporary support
       -> direct placement / native route / relevant jump / scaffold
       -> confirm server block update
  -> cleanup owned temporary blocks
  -> verify every requested structural state
  -> jobs.result -> event inbox -> action_finished / action_blocked
  -> musecraft command output / standby wake -> player side chat
```

The mod owns placement rules, collision, visibility, mining and movement.
The controller owns scheduling, inventory reservation, retries and the temporary
work journal. The agent-facing interface needs no changes.

## Scheduling and access

- Complete lower actionable layers before enclosing them. Prefer nearby work
  and placements that supply dependencies. A beam can grow from either end;
  a floating ordinary block can acquire a short removable support below it.
- Cache assessments by watched block cells. Relevant changes invalidate failed
  work; unrelated successful placements do not requeue every failed target.
- Door and bed footprints are checked against the whole design. Both halves
  can be explicit plan cells while consuming only one inventory item.
- Clear replaceable vegetation and matching explicit `replace` targets. After existing routes and temporary access have been tried, a
  reachable ray obstruction may be any breakable block type. Preserve final
  plan cells and reject unsafe clearance, liquids, unbreakable blocks and
  containers with unknown contents.
- Select tools/materials internally. Reserve unfinished final materials before
  allocating spare blocks for temporary support or access.
- Reuse a scaffold stance and completed roof. Construction routes taken from
  an owned pillar require reversible movement edges, so a one-way drop cannot
  strand its cleanup route. Cleanup removes supports visible from the current stance, returns to the pillar
  to descend, then approaches remaining supports from below.
  Cleanup repeatedly removes reachable owned supports and reassesses exposed
  ones; one inaccessible high support does not block removal of a lower support.
  Route failures are retried only after another owned support is removed.
- Work through finite, distinct alternatives. A failed search is an access
  limitation, not proof that a target is physically impossible.

## Results and recovery

The public result contract and stable reason codes are documented in
[`skill/references/api.md`](../skill/references/api.md). A partial result carries
verified/remaining counts, missing inventory quantities, representative blockers,
causes and any pending temporary-work cleanup. Results are written to
`build-results/<jobId>.json` before publication. These diagnostic files do not
provide controller-restart replay; the existing invocation receipt handles
interrupted/uncertain effects, and repair re-observes the world.

Every mutation checks the current action owner. Cancellation stops writes and
leaves recorded temporary work intact; repair reuses that access while finishing
the plan instead of requiring all temporary work to be removed up front.
Completion is only reported after cleanup and a final world-state verification.
Transient properties such as a door being open do not invalidate the design.

## Boundaries

This implementation clears local placement-ray obstructions and expected target
occupants. It does not implement a general excavation-aware pathfinder or build
long bridges/tunnels. Exhausted access returns a blocker so the agent can change
its plan. Temporary click support is a short vertical chain. Falling/attached
blocks still need permanent support in the final structure. Unopened container
contents are not synchronized to this client, so construction reports that fact
instead of assuming a chest is empty.
