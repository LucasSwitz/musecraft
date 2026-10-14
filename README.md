# Musecraft

**This file is written for a Muse agent.** A user pointed you at this
repository and asked you to install Musecraft. They will not read it. You do
the work, and you tell them the few things only they can do.

Musecraft lets you play Minecraft: Java Edition as a real player alongside the
user, on a server they both join. A Fabric mod drives a licensed Minecraft
client on this VM, a local controller runs your actions, and a dedicated side
chat plays through one `musecraft` command per decision. Idle play costs no
turns; you wake when the user chats or the game needs you.

## Before you start, tell the user

Give the user a short overview in your own words: setup takes about 20
minutes, you do almost all of it, and they will need to do four things, which
you will walk them through one at a time:

1. Approve network access when Muse asks (part A below).
2. Change one Muse setting so the game can reach the server (part B).
3. Sign in to Microsoft once in their own browser (part C).
4. Let your player onto their server, if it uses an allowlist (part D).

Then ask for anything you don't already know:

- **The server**: `host:port` of a Minecraft Java **1.21.1** server they can
  join. If they have no server, explain that they need one (a friend's, a
  hosting provider's, or their own);
  [skill/references/server.md](skill/references/server.md) is a tested recipe
  you can help them follow.
- **Their Minecraft name**: the player you follow and take requests from (your
  *owner*).
- **Your account**: a Microsoft account that owns Minecraft: Java Edition for
  you. It cannot be the account they play on, because one account can only be
  online once. Without a second account you cannot play; say so plainly.
- **Your avatar**: you play as **Jolly**, a custom character made with the
  Figura mod. Ask whether you may publish Jolly to Figura's public avatar
  service under your player's name, so players who have Figura see you as
  Jolly. It needs their yes because it publishes under that account. If they
  say no, you appear with the account's normal Minecraft skin.

## What the user does

The user never reads this file. When you reach each part, tell them exactly
what to tap and what they will see, one part at a time, and wait for them to
say it is done.

### A. Approve network access (throughout setup)

Muse shows an approval card each time you need a new site or server.

- Ask them to choose **Always allow** (or **Always allow this site**) whenever
  a card offers it. Then restarts and updates do not ask again.
- Some download cards ask to "access the web for this task" and offer only
  **Allow once**; that is fine.
- **Their server's card matters most.** When your player first connects, a
  card names the server's IP address and port (it may appear as `::ffff:`
  followed by the IP). They must choose **Always allow**, which covers only
  that address and port. **Allow once** would make every restart, reconnect
  and update ask again, and your player would sit disconnected until they
  answer.
- They will see cards for Minecraft, Fabric and Prism downloads
  (`resources.download.minecraft.net`, `github.com`), Microsoft, Xbox and
  Minecraft sign-in (`login.microsoftonline.com`, `xsts.auth.xboxlive.com`,
  `api.minecraftservices.com`), the Figura avatar service, Minecraft Realms
  (`pc.realms.minecraft.net`), and their server.
- If anything seems stuck, list pending approvals yourself and tell them which
  card is waiting, by the host it names.

### B. Allow direct connections (once, before your player starts)

Minecraft talks to the server over its own connection rather than the web.
Muse blocks those connections by default, without showing a card, until the
user allows them. Walk them through it while the client installs:

1. Open **Muse settings**.
2. Open **Permissions**.
3. Find the **Direct network protocols** section.
4. Find **Other TCP connections** ("Any other direct TCP connection on ports
   without a dedicated setting") and switch it from **Deny** to **Ask**.

Explain that **Ask** keeps them in control: each new server still needs their
approval card, and for their server they choose **Always allow** (part A). You cannot see this setting, so ask them to confirm they
changed it. If they skip it, your player cannot connect and no card appears.

### C. Sign in to Microsoft (once)

Install step 3 gives you a link and a code. Send both, then describe what they
will see:

1. On https://www.microsoft.com/link (any device), **Enter code to allow
   access**: type the code and continue.
2. Sign in with the Microsoft account that owns Minecraft **for you**, not the
   account they play on. Microsoft may ask for a password, a passkey (face,
   fingerprint or PIN) or a code sent to their email; they complete that
   themselves. Never ask them for a password or code.
3. If Microsoft signs them in automatically with a different account, they
   should open the link in a private or incognito window instead.
4. Microsoft may show **We're updating our terms**; they choose **Next**.
5. **Let this app access your info?** for **Prism Launcher**: they choose
   **Accept**. Prism Launcher is the Minecraft launcher you run, so this is
   expected.
6. **All done! You're now signed in to Prism Launcher.** They tell you it is
   done, then approve the Xbox and Minecraft cards that follow (part A).

A code expires after about 15 minutes. If they are late, make a new one and
send it; do not make them start over.

### D. Let your player onto the server (if it uses an allowlist)

Tell them your player name and ask them, or whoever runs the server, to
allowlist it: `whitelist add PLAYER` in the server console, or
`/whitelist add PLAYER` in game as an operator. The server must run
Minecraft Java 1.21.1.

### E. Play

They start Minecraft: Java Edition 1.21.1, join the same server and chat in
game. Your player answers in game chat, follows them and takes on tasks. They
can say "stop" in game when they are done.

If you uploaded Jolly, players without Figura still see the account's plain
skin. To see Jolly, they install Figura for Fabric 1.21.1, then open Figura's
**Permissions** page and set your player to **Trusted**; until then Figura
shows a shield badge next to your name and limits the avatar.

## Install

Run every command with your exec tool. `musecraft` is not on `PATH`; use the
full path shown.

### 1. Download and install the skill

```bash
mkdir -p ~/workspace/musecraft-download && cd ~/workspace/musecraft-download
for f in musecraft.tar.gz musecraft.tar.gz.sha256 install_skill.py; do
  curl -fsSLO "https://github.com/LucasSwitz/musecraft/releases/latest/download/$f"
done
sha256sum -c musecraft.tar.gz.sha256
python3 install_skill.py musecraft.tar.gz \
  --sha256 "$(cut -d' ' -f1 musecraft.tar.gz.sha256)" --skills-dir ~/workspace/skills
```

### 2. Install the game client

Run this as a background exec; it downloads several hundred megabytes and
takes about 10–15 minutes. While it runs, tell the user about approvals (part
A) and walk them through the direct-connections setting (part B).

```bash
~/workspace/skills/musecraft/install.sh --setup-prism \
  --home ~/workspace/musecraft-player --systemd-scope system
```

It ends with `[6/6] Setup complete` and creates
`~/workspace/musecraft-player/bin/musecraft`, written `MC` below.

### 3. Sign in

The user's side of this step is part C.

1. Run `MC login` as a background exec (`background=true`). It opens Prism,
   the Minecraft launcher, on a virtual display.
2. Run `MC screen shot` and read the PNG it prints. Prism shows an **Add
   Microsoft account** page; click that button with `MC screen click X Y`,
   using its center in the screenshot.
3. About ten seconds later, take another screenshot. It shows a **code** and
   the link **https://www.microsoft.com/link**. If a prompt for
   `login.microsoftonline.com` appears first, ask the user to choose
   **Always allow**.
4. Send the user the link and the code, and walk them through part C.
5. Take a screenshot every 20 seconds or so until Prism shows the account with
   its Minecraft name, or an error. A code expires after about 15 minutes;
   click the button again for a new one. If Prism stays on **Getting
   authorization…** for more than about 20 seconds, an approval is waiting:
   list pending approvals and ask the user to choose **Always allow**.
6. Click **Finish** if Prism shows it, then run `MC login stop` to close Prism.
   It prints the signed-in accounts; `MC accounts` shows them again. That
   Minecraft name is your **player name**.
7. Your player name must not be the owner's name. If it is, or there is more
   than one account, ask the user which account is yours. To add another
   account, repeat this step and click **Accounts → Manage Accounts → Add
   Microsoft** in Prism. Then, with Prism closed, run `MC accounts keep PLAYER`;
   it signs out every other account and makes yours the default. (Prism's own
   **Remove** stays disabled for an account signed in during the current
   Prism session.)

### 4. Start your player

First make sure the user has changed the direct-connections setting (part B)
and, if their server uses an allowlist, added your player name (part D).

Create a dedicated side chat with your chat tools: `chat.create` with
`context_mode: "fresh"` and the name `Minecraft`. Then send it, with
`chat.send_message`:

> Load the musecraft skill and follow its SKILL.md. Play on server HOST:PORT
> as PLAYER with owner OWNER, using the headless client:
> client={"display":":95","headless":true}

That side chat is the player. It starts the game, joins the server and stays
ready. If you cannot create a side chat, ask the user to open a new chat and
send that same message there.

When the game first connects, Muse asks to approve the server's IP address and
port, plus direct connections to `api.minecraftservices.com` and
`pc.realms.minecraft.net`. Tell the user these cards are coming and that the
server's card needs **Always allow** (part A); otherwise every relaunch asks
again. No card appears while **Other TCP connections** is
still **Deny** (part B); the player then reports that it cannot connect.

### 5. Hand off

Nothing wakes you when the player joins, so wait for it with a background
exec. It ends, waking you, once the player is in the game, or after 15
minutes:

```bash
for i in $(seq 90); do
  MC session status | grep -q '"status":"playing"' && { echo joined; exit 0; }
  sleep 10
done; echo "not joined after 15 minutes"
```

If it does not print `joined`, read the side chat's replies and list pending
approvals to find out what is blocking it.

Once joined, and if the user agreed to publish your avatar, upload Jolly. The
player must be in the game:

```bash
MC avatar load ~/workspace/musecraft-player/prism-data/instances/Musecraft/minecraft/figura/avatars/Jolly
sleep 5; MC avatar upload
MC avatar reload
sleep 10; MC avatar status
```

Each prints JSON with `"ok":true`. The last must show `"name":"Jolly"` and
`"source":"backend"`, which means Figura's service now serves it. If `upload`
fails with `figura_backend_not_ready`, list pending approvals (the game needs
`figura.moonlight-devs.org`), wait 30 seconds and run `upload` again. You only
upload once per account; updates and restarts keep it.

Then tell the user it is ready and how to play (part E).

## Update

When the user asks to update Musecraft:

1. Ask the Minecraft side chat to stop for an update (`musecraft stop`) and
   wait until it confirms; the client and controller must be stopped.
2. Repeat install step 1 in a new download folder. The installer keeps the old
   skill as `~/workspace/skills/.musecraft.previous`; move that aside first if
   it already exists.
3. Rerun the install step 2 command. It reuses verified downloads and takes
   under a minute.
4. Ask the side chat to resume play. Sign-in and approvals carry over.

## If something goes wrong

- `play_blocked: ... white-list` (or "not allowlisted"): the user must allowlist
  your player name on their server (part D).
- The player stays "not ready" or "recovering", or the client log shows
  `Connection closed during protocol change` or `Failed to retrieve profile key
  pair`: **Other TCP connections** is still **Deny**, or the server's approval
  was not allowed. Walk the user through part B again, then check pending
  approvals.
- Version or protocol errors: the server must run Minecraft Java 1.21.1.
- The client does not reach the title screen, or reports no account: repeat
  step 3. If Microsoft says the account has no Minecraft profile or offers a
  demo, the account does not own Minecraft: Java Edition; ask the user for the
  right account.
- Downloads fail: check for pending approvals, then rerun the same command;
  verified files are kept.
- More: [skill/setup.md](skill/setup.md) (installation, sign-in, approvals,
  troubleshooting) and [skill/SKILL.md](skill/SKILL.md) (how the player plays).

## How it works

```text
Muse side chat (the player)
  │  musecraft play | <action> key=value | standby | stop
  ▼
musecraft CLI ──Unix socket──▶ controller (systemd service in the runtime cell)
                                 │ Unix socket
                                 ▼
                    Fabric mod + Baritone in a licensed 1.21.1 client (Prism, Xvfb)
                                 │ TCP
                                 ▼
                    Minecraft 1.21.1 server (run separately)
```

No game files, accounts or worlds are distributed here. Setup downloads
Minecraft from its publishers.

## For maintainers

### Build and publish a release

Needs JDK 21 (`JAVA_HOME`), Gradle 8.12, Node 25 and Python 3.12+. Bump
`version` in `release.json` and commit first; builds come from a clean,
committed tree and a version is never reused.

```bash
python3 scripts/check.py
for t in tests/*.cjs; do node "$t"; done
python3 scripts/release.py            # --gradle PATH, --offline
python3 tests/release_contract.py dist/musecraft-VERSION.tar.gz
```

Publish `dist/musecraft-VERSION.tar.gz` and its `.sha256` as a GitHub release,
plus the fixed-name copies the install steps fetch: `musecraft.tar.gz`,
`musecraft.tar.gz.sha256` (listing `musecraft.tar.gz`) and
`scripts/install_skill.py`. CI (`.github/workflows/release.yml`) runs the
checks and tests on every push, and a `v<version>` tag creates a **draft**
release.

Builds do not prove sign-in, approvals or gameplay. Before publishing, install
from the repository on a fresh Muse VM as a user would, then play: send at least
30 chat requests across idle, following and building or mining, and record the
reply times, quiet periods, restarts and `musecraft stop`.

### Repository layout

| Path | What |
| --- | --- |
| `mod/` | Fabric client mod (Java 21, Gradle) with bundled Baritone 1.11.3 (`mod/libs/`). |
| `skill/` | The shipped skill: `SKILL.md`, `setup.md`, `install.sh`, `references/` (player prompt, API, server, avatars) and `scripts/` (controller, CLI, installer and screen helpers). |
| `avatar/` | Source for the Figura avatars in `skill/avatars/`. |
| `scripts/` | `release.py` (build), `check.py` (source checks), `install_skill.py` (install a release). |
| `tests/` | Controller contract tests. |
| `docs/` | Subsystem references: events and recovery, navigation, building. |
| `release.json` | Version and pinned Minecraft, Fabric, Java, Node and protocol. |

### License

See [LICENSE](LICENSE). Baritone is bundled unmodified under LGPL-3.0; see
[mod/libs/README.md](mod/libs/README.md).
