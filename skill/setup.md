# Install Musecraft

This guide installs the Musecraft Fabric mod and controller next to a licensed
Minecraft: Java Edition client, signs in, and connects to a server. To start
playing afterward, follow [SKILL.md](SKILL.md).

Musecraft ships its own controller and mod, **not Minecraft or account
credentials**. Setup downloads game files from their publishers. Sign-in stays
in the unmodified Prism Launcher and Microsoft's own pages.

## Requirements

- **A Microsoft account that owns Minecraft: Java Edition.** Setup does not
  buy an account or accept agreements for the user. A separate licensed
  account is useful if the agent and the user should play at the same time.
- **Linux x86_64 with systemd and a display.** Use a desktop, or Xvfb for
  headless play (see [Desktop or headless](#desktop-or-headless)). Windows and
  macOS installers are not available.
- **Minecraft 1.21.1, Fabric Loader 0.16.14, Java 21 and Node 25.** Quick setup
  downloads and verifies all of them for you: Prism 11.1.0, Java 21, Minecraft
  1.21.1, Fabric 0.16.14, game assets and Node 25.9.0. Existing-client installs
  bring their own Minecraft and Fabric (see
  [Existing clients](#existing-clients-and-the-official-launcher)).
- **Machine packages:** Python 3.12+, curl, OpenSSL, tar, unzip, xz-utils,
  util-linux, systemd, D-Bus and OpenGL. On Debian/Ubuntu, an administrator can
  install them with:

  ```bash
  sudo apt-get update
  sudo apt-get install python3 curl openssl ca-certificates tar unzip xz-utils util-linux \
    dbus-x11 libgl1-mesa-dri libopengl0 libxtst6 libsecret-1-0 gnome-keyring
  ```

  Headless play also needs Xvfb (`xvfb`) with Mesa software rendering, plus a
  way to view and control that display for the one-time sign-in. The installer
  does not create a VM or desktop.
- **A Minecraft 1.21.1 server you are allowed to join.** Musecraft runs the
  client, controller and mod; it does not host a server.
  [references/server.md](references/server.md) describes a reference server.

Use the same user (and, on Muse, the same runtime cell) for the launcher, the
game and the controller.

## 1. Install

Close Prism and Minecraft. From the unpacked release directory, run:

```bash
./install.sh --setup-prism --home "$HOME/workspace/musecraft-player"
```

This downloads the toolchain listed above, installs the bundled Musecraft mod,
and configures the **Musecraft** Prism instance and its Java arguments. You do
not need to install Fabric in the UI, edit JVM settings or compile anything.
Downloads can take several minutes. If setup is interrupted, rerun the same
command: verified files are kept and damaged files are replaced.

The `--home` directory holds the controller, its state and the
`bin/musecraft` command. Installer options:

| Option | Effect |
| --- | --- |
| `--home DIR` | Absolute controller home (default `$HOME/workspace/musecraft`). |
| `--prism-data DIR` | Reuse an existing Prism data directory and its saved login. |
| `--systemd-scope user\|system` | Where the controller service runs (default `user`, or `system` as root). |
| `--vm-performance` | Reversible low-resource graphics preset (see [Performance](#performance-and-render-distance)). |
| `--prism-instance DIR` / `--minecraft-dir DIR` | Install into an existing client instead of `--setup-prism`. |

Every `musecraft` command starts the controller service
(`musecraft-controller.service`) when it is not running.

## 2. Sign in

Credentials stay in Prism and Microsoft's sign-in pages. Never put them in
files, chat or command arguments.

1. On a Muse VM or any headless machine, run `musecraft login` as a background
   exec: it opens Prism (without the game) on the client display. See it with
   `musecraft screen shot` (read the PNG), and click or type with
   `musecraft screen click X Y`, `musecraft screen type TEXT` and
   `musecraft screen key NAME`. A fresh Prism opens on an **Add Microsoft
   account** page; otherwise use **Accounts → Manage Accounts → Add →
   Microsoft**. Prism then shows a code and https://www.microsoft.com/link: give
   both to the user, who completes Microsoft's page in their own browser. If
   Prism then stays on **Getting authorization…** for more than about 20
   seconds, a network approval is waiting: check pending approvals and ask the
   user to choose **Always allow**. Click **Finish** when the account appears,
   then run `musecraft login stop`; the sign-in command prints the signed-in
   accounts as it ends. On a desktop, run `bin/launch-minecraft` instead and use
   the same menus.
2. Keep passwords, MFA and recovery codes in Microsoft's UI. Do not request
   credentials in chat, read or copy launcher account files yourself, or
   inspect authenticated process arguments. `musecraft accounts` shows only
   account names. Pause any recording during credential entry.
3. Verify the expected Minecraft profile and Java Edition entitlement with
   `musecraft accounts`. The player's account must not be the owner's, since
   one account can be online only once. If the wrong account was signed in,
   run `musecraft login` again to add the right one, close Prism, then run
   `musecraft accounts keep NAME`: it signs out every other account and makes
   NAME the default. Prism's own **Remove** stays disabled for an account
   signed in during the current Prism session. If Prism offers a demo or says `No Minecraft profile`, resolve
   ownership and Java profile creation on Minecraft's official account page.
   After a license or profile change, repeat Prism's normal sign-in if its
   state remains stale.
4. Launch **Musecraft** once to confirm the game starts, then return to the
   title screen. A device code or Microsoft login alone does not prove a
   playable Java profile.

The user owns the account and its recovery methods. If Microsoft later asks for
sign-in again, hand control back to the user; automatic reconnection never
renews credentials.

To reuse an existing Prism login without touching credentials, close Prism and
pass its data directory:

```bash
./install.sh --setup-prism --home "$HOME/workspace/musecraft-player" \
  --prism-data /absolute/existing/prism-data
```

Setup creates only its **Musecraft** instance and public downloads. It does not
read or write account files (only `musecraft accounts keep` edits them), and it refuses to replace an unrelated instance
with that name. Do not move accounts to another machine as a setup shortcut.

## 3. Connect to a server

Get the server's hostname and port. The server must run Minecraft 1.21.1 and
admit the licensed player (allowlist, bans and server rules still apply).
`musecraft play` joins the server itself, so leave the client at the title
screen or already connected to that same server. Use the same player name as
the signed-in profile, and set `owner` to the Minecraft name of the person the
agent plays with: it follows that player's in-game requests.

### Desktop or headless

**Desktop.** A person runs the client on a visible desktop. Start the launcher
from the desktop session (it needs `DISPLAY`), launch **Musecraft**, then start
play without `client`:

```bash
"$HOME/workspace/musecraft-player/bin/launch-minecraft"
"$HOME/workspace/musecraft-player/bin/musecraft" play server=HOST:25565 player=PLAYER owner=OWNER
```

After a reboot, restore that desktop and launcher yourself.

**Headless (managed client).** Musecraft starts and supervises the prepared
Prism instance for you:

```bash
MC="$HOME/workspace/musecraft-player/bin/musecraft"
"$MC" play server=HOST:25565 player=PLAYER owner=OWNER client='{"display":":95","headless":true}'
"$MC" client    # long-running; keep it running in the background
```

`musecraft play` saves its settings (server, player, owner, client) in the home
directory, so later runs need no arguments. `musecraft client` holds the game
for as long as it runs and exits only if the game does; run it again to
restart. It adopts an already running client instead of restarting it. With
`"headless":true` it starts Xvfb on the display when none is running; with
`"headless":false` it requires an existing desktop on that display. Initial
sign-in and any fresh network approvals still use the normal UI.

### Verify

```bash
"$MC" state            # current state; look for "ready"
"$MC" session status   # connection and recovery state
"$MC" observe          # latest world snapshot (needs an active play session)
```

`musecraft play` prints `Playing on HOST as PLAYER with OWNER.` followed by the current
state once the player is in the world. Installation alone does not prove
account entitlement or a successful join. Choose a safe location before moving
or changing blocks.

The controller reconnects after transient network failures (backing off up to
60 seconds) and respawns after death. It reports persistent account, version,
allowlist and network problems as a blocked session. After fixing one, run
`musecraft session retry` (or `musecraft play` again), or `musecraft stop` and start again
with corrected settings. To change the server or player, run `musecraft stop`
first. After `musecraft stop`, nothing restarts play until someone runs
`musecraft play` again. Automatic reconnection does not renew account
credentials or grant network access.

To let an agent play, install the skill into its skills directory (see the
Musecraft source repository's README) and follow [SKILL.md](SKILL.md).

## Running on a Muse VM

Install in the workspace, as the runtime cell's user, with the system
controller scope:

```bash
./install.sh --setup-prism \
  --home /home/hatch/workspace/musecraft-player --systemd-scope system
```

Use the VM desktop's `DISPLAY` (the examples use `:95`, the `musecraft client`
default). Do not run the launcher on the VM host or as a separate systemd
service.

Launch the client from a **fresh managed `exec` session** with
`background=true`, either `musecraft client` (headless mode) or the launcher
for a desktop:

```bash
DISPLAY=:95 /home/hatch/workspace/musecraft-player/bin/launch-minecraft
```

The client needs that exec's network access, which is why it runs inside a
long-lived command rather than a service. Fully quit any previous Prism process
first: a new launch can forward to an old process whose execution
authorization has expired. Keep the managed exec running while the launcher is
in use. Inherit the runtime's proxy and CA environment; do not print, save or
copy its authorization. Setting a proxy hostname alone does not grant access.

The launcher wrapper keeps TLS verification and uses the standard managed
proxy. Setup stores hash-verified Prism component documents in the instance's
local `patches` directory, pinning the tested versions without a metadata
refresh at every launch. It does not change Prism, DNS or authentication
endpoints, and it does not create a local proxy. When `SSL_CERT_FILE` is set,
the wrapper combines that CA bundle with the system roots in a temporary hashed
directory and exposes it read-only at `/etc/ssl/certs` in a private user/mount
namespace, because some Qt builds (including Prism 11.1.0) ignore
`SSL_CERT_FILE` and `SSL_CERT_DIR`. Linux user namespaces and `unshare` must be
available. If the private mount fails, launch stops with an error; it never
skips certificate validation. The host trust store is unchanged, and the
temporary directory is removed when Prism exits. Without `SSL_CERT_FILE`, the
launcher starts directly with the system roots.

### Network approvals

The launcher needs approved HTTPS access for public downloads and for
Microsoft, Xbox and Minecraft authentication. A proxy setting supplies a
transport; it does not approve destinations or replace account sign-in.

Joining a server also needs outbound TCP to the server's host and port
(usually 25565). Game traffic is not HTTPS and is not carried by
`HTTPS_PROXY`; the game's profile-key request to `api.minecraftservices.com`
also connects directly. Muse drops direct TCP without an approval until the user
opens **Muse settings → Permissions → Direct network protocols** and switches
**Other TCP connections** from **Deny** to **Ask**. Until then the client log
shows `Connection closed during protocol change` and `Failed to retrieve
profile key pair`. Then resolve the exact server TCP request in Muse's network
approvals after the fresh launcher tries to connect:

- For a server Musecraft should rejoin routinely, choose **Always allow** for
  that exact host, TCP port and protocol, then confirm it appears in the active
  permissions as **durable**.
- **Allow for this session** is tied to the managed launcher's execution; a new
  launcher or exec session needs a new grant even for the same server. A
  Minecraft play session is not that execution scope.
- Approve the endpoint shown on the request. Java may display an IPv4 server as
  an IPv4-mapped IPv6 host (`::ffff:ADDRESS`), and some Java HTTPS traffic
  appears as an opaque `tcp` request on port 443.
- Broadly opening ports is unnecessary. Approvals are a user/operator setup
  step, never something the player grants itself.

Apply the same exact-destination durable choice to the recognized Minecraft,
Microsoft/Xbox authentication, profile and download requests made by the
launcher and installed mods. Check pending requests again after joining:
skin/profile and Realms requests can arrive after authentication (observed
destinations include `api.mojang.com`, `sessionserver.mojang.com` and
`pc.realms.minecraft.net` on HTTPS 443). An unresolved request can reach the
runtime's approval hold limit and end the whole managed launcher, even while
gameplay works. Resolve only recognized requests from the current
launcher/Java process; never approve requests from old processes or disable
TLS verification. If the launcher was terminated, keep its account files and
relaunch through a fresh managed exec before resolving new requests.

To confirm permissions persist, relaunch through a fresh managed exec and then
restart the server: the client should rejoin with no new matching pending
approvals. Keep the binary and install path stable, and recheck grants after
moving the installation or changing the server address.

A VM startup or OS package-recovery failure comes before Minecraft setup. Restore
the VM baseline and desktop before retrying the launcher, and keep the
workspace, Prism account and instance. Resetting user state is not a Minecraft
installation repair.

## Updating

1. End play with `musecraft stop reason="updating Musecraft"`. Standby ends by
   itself.
2. Stop the client: end the `musecraft client` command, or save and quit
   Minecraft and Prism on the desktop.
3. Stop the controller with
   `systemctl --user stop musecraft-controller.service` (drop `--user` for
   `--systemd-scope system`). Setup refuses to run while the game or
   controller is live, and any `musecraft` command restarts the controller, so
   run none until setup finishes.
4. Replace the whole skill directory with the new release and rerun the same
   install command against the existing home and Prism instance. The controller
   scripts and the mod must be updated together: they check each other's
   release and protocol versions (`musecraft_version_mismatch`). Worlds,
   accounts and other client mods are preserved; only jars whose Fabric ID is
   `musecraft` are replaced.
5. Start again with `musecraft play` (and `musecraft client` in headless mode).

If a restarted launcher reports that a network approval has no session scope,
quit any surviving Prism process and launch again through a fresh managed exec.
Prism can forward a new launch to an old process that keeps its expired network
scope. Keep its account and instance files, and approve only the new launch's
destinations.

## Existing clients and the official launcher

For an already prepared Prism instance running Minecraft 1.21.1 with Fabric
Loader 0.16.14:

```bash
./install.sh --prism-instance /absolute/instance --home /absolute/controller-home
```

This installs the mod and controller and prints a Java argument
(`-Dmusecraft.home=...`). Add it in **Edit Instance → Settings → Java** with the
**Java Arguments** override enabled. Quick setup does this automatically.

For the official launcher, download it from
[minecraft.net](https://www.minecraft.net/en-us/download), sign in, run Java
1.21.1 once, quit, and install with `--minecraft-dir /absolute/.minecraft`. This
mode needs a full JDK 21 (for Fabric's installer); select the
`fabric-loader-0.16.14-1.21.1` profile and add the printed JVM argument. On the
tested Muse VM the official launcher's embedded Microsoft sign-in did not
complete, so use Prism there.

Existing-client installs still download Node 25.9.0. They do not install
Minecraft, accept its EULA, sign in, create a server or change account files.

The release bundles the mod JAR and controller together. `manifest.json`
records every payload hash and the source revision, and installation verifies
the whole payload before downloading anything. To build a release from source,
see the Musecraft source repository's README; the source `skill/` directory has no prebuilt mod
JAR.

## Performance and render distance

The 128-block visibility scan needs both the client render distance and the
server's `view-distance` to be at least **8 chunks**. Server owners configure
their server separately. Simulation distance can stay lower. Scans report
unloaded chunks rather than inventing unseen terrain.

On a small VM with software graphics, add `--vm-performance` to either install
mode. It sets an 854×480 window, a 30 FPS cap, six-chunk render and simulation
distance, fast graphics, fewer particles and normal maximum brightness, and
hides the keyboard tutorial (which native movement cannot complete). Close
Minecraft before applying it. The original `options.txt` is kept as
`options.before-musecraft-vm.txt` in the game directory; restore it while the
game is closed to undo the preset. Benchmark the preset on the actual machine:
rendering settings do not establish gameplay correctness.

## Server availability

Use a DNS name or a reserved public IP for a cloud server. If an ephemeral
address changes, update the play settings (`musecraft stop`, then
`musecraft play server=...`), the saved server entry and the exact outbound
TCP approval together. Check the server host's runtime limits and scheduled
shutdowns before a long session. A running client does not prove the remote
server is up or reachable.

## Troubleshooting

- **Download failure:** rerun the same setup command. If one stage keeps
  failing, check its named endpoint. Do not disable TLS verification.
- **407 / expired authorization:** fully quit Prism, then start the launcher
  through a fresh managed exec. Do not save proxy credentials.
- **Display/OpenGL error:** check that the desktop (or Xvfb) is running and
  `DISPLAY` is correct.
- **Client socket missing:** check that the launcher and controller share the
  same user and cell and that the mod is loaded.
- **`not_playing` or `play_session_changed`:** run `musecraft play` first.
- **Not ready:** the player must be in a world; the title screen is not ready
  for gameplay.
- **`play_blocked` / blocked session:** fix the reported account, version,
  allowlist or server problem, then retry as described in [Verify](#verify).
- **Another installation already running:** wait for that setup to finish.
- **Controller did not become ready:** check the
  `musecraft-controller.service` logs and stop any controller belonging to
  another installation.

## Tested configuration

Last validated end to end on a Muse VM on 2026-10-12 with 0.8.0-rc.102: an
update over an existing installation (`install.sh` rerun against the same home
and Prism data), headless client on `:95`, an external 1.21.1 server, and a
player in a side chat answering real signed chat from a second licensed client.
Replies took a median of about 6 seconds, idle play made no model calls, and
pause and resume through `musecraft stop` and `musecraft play` kept the world,
inventory and settings.

Fresh installation was last validated on 2026-09-15: empty installation directories,
interrupted-download recovery (including automatic repair of a deliberately
damaged asset), a fresh Prism account-add screen, and setup from Muse's normal
managed execution with an existing licensed account. The configured client
entered a new singleplayer world; camera, movement, digging, controller startup
on the first command and saved controller state across restart passed, with
spaces in the install path. That run did not cover a blank OS install, a new
Microsoft sign-in or an external server join.
