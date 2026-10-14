# Continuous play

How one agent conversation keeps playing while the game runs continuously. The
player is an agent in a dedicated side chat that runs `musecraft` commands;
deterministic code owns observations, connection state and action execution.

```text
Minecraft mod -> private Unix socket -> controller (event inbox + latest world)
  -> open musecraft command returns with events + state
  -> or musecraft standby exits -> agent wakes in the same side chat

musecraft <action> -> CLI schema validation -> controller durable receipt
  -> replace previous physical action -> native mod
```

## Responsibilities

| Component | Responsibility |
| --- | --- |
| Mod | Client state, signed chat, nearby entities, native movement and item control |
| Controller | Play session, one physical action, durable invocation receipts, event inbox, reconnect and normal respawn, standby wake decisions |
| CLI | `key=value` parsing and schema validation; prints results, events and state. The player passes no identifiers. |
| Agent runtime | Background execs for `musecraft client` and `musecraft standby`; wakes the agent when a background command finishes |
| Player | Acknowledge the human, choose high-level actions and inspect completion evidence |

`musecraft chat` and reads leave the current action running. A new physical
action replaces it; `musecraft queueAttack` appends without replacing combat.
Ending a turn leaves work running. `musecraft stop` records an explicit end and
disconnects when the human asks to stop playing.

## Events

Events are recorded durably with a 200 ms batching window. Only those that
need a decision wake the player: the owner's chat or chat naming the player,
damage or an escalating threat, a terminal outcome of the player's action,
death, respawn, joining, reconnection, and the owner joining or leaving.
Completion, failure, blocked work and unexpected interruption all count as
terminal outcomes; replacing an action is expected and stays quiet. Everything
else (other chat, presence, morning, threat updates) waits for the next output.

Initial connection emits one `ready` event. Connection recovery emits one
durable `ready_again` event when the client is ready again; it carries
`previousClientEpoch`, and the world snapshot carries the new identity and last
interrupted action. Repeated ready frames and replay do not produce more
events. Respawn emits one durable `respawned` event after an observed death once
the player is alive and ready to act; it includes the death location, and fresh
state supplies the new position and inventory. The pending transition survives
a controller restart. Each newly observed Overworld day emits one
normal-priority `morning` event once alive, awake and ready, including after
sleep skips the night; a durable day cursor prevents repeats across restarts
and reconnects. Movement, inventory and other ambient changes stay quiet.
Server chat remains in the native log but does not wake the player.

Snapshots include complete inventory and up to 32 nearby entities within 32
blocks, with omission metadata, plus `nearbyBlocks` (`atFeet`, `belowFeet` and
the nearest representatives of up to 12 block types in a radius-4 cube). This
bounded loaded-world sample includes cells behind walls; `musecraft scanBlocks`
provides individual positions and filtering. While the player is deciding,
ordered events queue and new world state replaces older pending state. No
parallel decisions compete for one body.

The controller retains 512 events, offers at most 24 at once, and keeps an
offer stable until it is acknowledged; in CLI play, the next `musecraft`
command acknowledges the output the previous one printed. Overflow produces an
explicit gap event.

## What the player sees

The controller renders a deterministic brief from the world snapshot whenever
it offers an event batch: timestamps, readiness, player condition and
inventory, current action and previous result, nearby entities and blocks, and
an open container. Bounds include explicit omission counts. Game text is
escaped as data, and no model summarizes or chooses the state representation.
`musecraft inventory`, `entities`, `container` and `scanBlocks` provide detailed
reads; `musecraft observe` prints the full diagnostic world.

Each command prints its result, then any new events and a one-line status; the
full state follows joining, death, respawn, reconnection and `musecraft state`.
An offer's events and state stay identical across retries and restarts. A
physical action keeps its command open until it finishes or something needs the
player (at most 15 minutes); `follow`, `flee`, `attack` and `queueAttack` return
once started. A physical action or wait that starts within a second of output
with waking events is the rest of the same shell chain and is skipped, so the
player reads those events before acting.

## Waking the player

Idle play costs no turns. The agent ends each turn with `musecraft standby`
running in the background. Standby exits within about half a second of a waking
event, once no command is open and 1.5 seconds have passed since the last one,
or when the managed client is down; that exit wakes the agent, and its output
carries the events and status so the agent can reply in one step. An exit while
the agent is still mid-turn folds into that turn. A waking event also returns
any open command at once. The first danger
requests one urgent decision; follow-up threats stay coalesced in the state and
command results until the encounter has been clear for ten seconds. Actions run asynchronously, so the player can respond
while the body is moving, though inference still takes time. The player
prioritizes attack or flee before acknowledgements and planning when damage or
level-2/3 danger arrives.

## Recovery and stopping

The CLI supplies invocation IDs, sequence numbers, the session generation and
the observed world identity from controller state. The controller records
admission before effects, rejects stale actions and returns saved results for
identical retries. A crash during an unconfirmed effect returns uncertainty; it
never silently repeats the effect. Named build plans can be repaired after
inspecting current state.

The world identity also carries the native client tick and latest player chat
ID. If a queued older frame repeats the same physical method and arguments for
the same player request, the controller returns the existing action's current
result without cancelling or repeating it. A fresh frame, different action, or
new player request can deliberately repeat an action. Missing action state
after a restart returns uncertainty.

`musecraft play` gives the play session to the controller, so it needs no
renewals while the controller runs. Startup has no total readiness deadline.
Temporary network failures retry with backoff capped at 60 seconds. Account,
allowlist, version and hardcore-respawn failures remain explicit blockers.
Connection loss does not mean the player chose to stop. `musecraft stop` ends
play durably and waits briefly for the disconnect to be confirmed.

## Validation

`tests/invocation_contract.cjs` covers receipt replay, uncertain effects,
action replacement, read/chat independence and stale-world/session fences.
`tests/event_inbox_contract.cjs` covers durable offers, chat retention,
coalescing and quiet-state suppression. `tests/session_contract.cjs` covers
cancellation races, durable stop, resume and reconnect backoff;
`tests/threat_delivery_contract.cjs` covers the single danger decision, and
`tests/cli_session_contract.cjs` covers the wake rules, standby and compact
output.

Live validation uses real signed chat from a second licensed client, records
median/p95/max reply latency and missing replies, and exercises native
gameplay, controller restart, reconnection and explicit stop.
