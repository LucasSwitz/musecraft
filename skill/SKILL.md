---
name: musecraft
description: Play Minecraft Java with a visible client, dedicated gameplay tools, and in-game chat. Use when asked to play Minecraft.
---

# Musecraft

Musecraft is played through one command: the installed `<home>/bin/musecraft`
(with the recommended Muse setup, `~/workspace/musecraft-player/bin/musecraft`),
written `musecraft` below. It is not on `PATH`: use the full path in every
exec. Use [setup.md](setup.md) to install the licensed
client and connect to a separate server. Keep credentials in the launcher and
Microsoft's sign-in UI.

## Start playing

Play from a dedicated side chat: that chat's agent is the player, and it keeps
playing until the human asks it to stop.

1. Read [references/player-prompt.md](references/player-prompt.md) and follow
   it for the rest of the chat.
2. Join the server. The first run needs the server, the licensed player name
   and the owner: the Minecraft name of the person you play with (ask the human
   for any you don't know). Later runs reuse them:

   ```sh
   musecraft play server=HOST:25565 player=AGENT_NAME owner=OWNER_NAME client='{"display":":95","headless":true}'
   ```

   Omit `client` when a person runs the game client on a desktop, and skip
   step 3.
3. With `client` set, start the prepared Prism client and leave it running:
   run `musecraft client` and let it move to the background (a short exec
   yield). It holds the game for as long as it runs. If it ever exits, or a
   command reports that the client is not running, run it again. If it exits
   because no Microsoft account is signed in, sign in first with
   `musecraft login` ([setup.md](setup.md#2-sign-in)).
4. Run `musecraft standby` in the background (a short exec yield) and keep it
   running whenever you end a turn. Make it the whole exec command, with no
   `nohup`, `&` or redirection, so the exec ends when standby does. It stays
   quiet while nothing needs the player, so idle play costs no turns. It exits
   when something does (the owner's chat, chat naming the player, danger, an
   action finishing, blocking or being interrupted, death, respawn, joining,
   reconnection, the owner joining or leaving, or the client down), and its
   completion is the wake-up: its output
   starts with "Minecraft needs you" and lists the events. Act on them and
   start standby again before ending the turn. Commands print a reminder while
   standby is off.

## Playing

Every decision is one command. Values are JSON when they parse, text otherwise:

```sh
musecraft gather item=minecraft:oak_log count=8
musecraft goto target=[120,64,-35]
musecraft attack entityId=4123
musecraft chat text="On my way"
musecraft wait            # rarely needed: block until something needs you
musecraft state           # full current state
musecraft help            # every action; `musecraft help gather` for one
```

Each command prints its result, any new events and a one-line status (health,
position, action and where the owner is); `musecraft state` prints the full
state. A physical action replaces the running one; reads and chat leave it
running. Long actions keep the command open until they finish or something
needs the player, by the same rule as standby; `follow`, `flee`, `attack` and
`queueAttack` return once started, and their outcomes wake the player. If the command moves to the
background, end the turn or keep chatting: its completion wakes the player with
the result. The controller tracks sessions, sequence numbers, event delivery and
reconnection internally; the player never passes identifiers.

## Stop and recover

- When the human asks to stop, the player runs `musecraft stop reason="..."`.
- The controller reconnects after network loss and respawns after death;
  standby wakes the player to rerun `musecraft client` if the game exits.
  Standby survives controller restarts and ends by itself when play stops.
- Running `musecraft play` again resumes the same play session, including after
  a blocker is fixed. Pass `owner=NAME` to change the owner.
- For diagnostics, `musecraft observe` prints the latest world snapshot and
  `musecraft session status` the connection and recovery state.
- Setup and account changes belong to the person or supervising agent, not the
  player.

## References

- [references/player-prompt.md](references/player-prompt.md): the player's
  standing instructions.
- [references/api.md](references/api.md): every command and action, with
  results, blockers and events.
- [setup.md](setup.md): installation, login, version and network issues.
