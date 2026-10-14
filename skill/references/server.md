# Minecraft server setup

Musecraft plays on a separate Minecraft Java server; the Muse VM only runs the
client, controller and mod. Use any 1.21.1 Java server that meets the
[requirements](#requirements), or build the reference server below, which is
the configuration Musecraft is tested against.

## Requirements

- Minecraft Java **1.21.1**, `online-mode=true` (the player signs in with a
  licensed Microsoft account) and `enforce-secure-profile=true`.
- `view-distance` **≥ 8** (the 128-block block scan needs 8 loaded chunks);
  `simulation-distance` can be lower (the reference uses 4).
- The player account allowlisted, plus any human players. Op only humans who
  run server commands; the Muse player does not need op.
- A stable address: a reserved IP or DNS name. If it changes, update the play
  session's `server` arg, the saved server entry and the VM's outbound approval.
- Optional: a Fabric server with Fabric API. The Musecraft mod and Figura are
  client-only; vanilla servers work.

## Reference server (GCP)

| Setting | Value |
|---|---|
| Machine | `e2-medium` (2 vCPU, 4 GB), 15 GB disk |
| OS / Java | Ubuntu 24.04 LTS, OpenJDK 21 (`/usr/bin/java`) |
| Address | reserved external IP, port 25565 |
| Firewall | tcp:25565 from `0.0.0.0/0`; tcp:22 from `35.235.240.0/20` (IAP SSH only) |
| Runtime limit | `maxRunDuration` 43200 s (12 h), `instanceTerminationAction: STOP` |
| Server software | Fabric server launcher (`fabric-server.jar`), loader 0.16.14, MC 1.21.1 |
| Server mods | optional `fabric-api.jar` |

The game port is open to the internet; the allowlist (`white-list=true`,
`enforce-whitelist=true`) and online-mode are the only gate. Narrow the source
range if the clients have fixed egress addresses.

### Create the instance

Set `PROJECT` to your GCP project; the other names are suggestions.

```bash
PROJECT=your-gcp-project; ZONE=us-central1-a; REGION=us-central1; NAME=musecraft-server
gcloud compute addresses create $NAME-ip --project $PROJECT --region $REGION
gcloud compute instances create $NAME --project $PROJECT --zone $ZONE \
  --machine-type e2-medium --image-family ubuntu-2404-lts-amd64 --image-project ubuntu-os-cloud \
  --boot-disk-size 15GB --tags $NAME --address $NAME-ip \
  --max-run-duration 43200s --instance-termination-action STOP
gcloud compute firewall-rules create $NAME-game --project $PROJECT \
  --allow tcp:25565 --target-tags $NAME --source-ranges 0.0.0.0/0
gcloud compute firewall-rules create $NAME-iap-ssh --project $PROJECT \
  --allow tcp:22 --target-tags $NAME --source-ranges 35.235.240.0/20
gcloud compute addresses describe $NAME-ip --project $PROJECT --region $REGION --format='value(address)'
```

The last command prints the server address to give `musecraft play`. SSH only
through IAP: `gcloud compute ssh $NAME --project $PROJECT --zone $ZONE
--tunnel-through-iap` (direct port 22 times out).

### Install the server

```bash
sudo apt-get update && sudo apt-get install -y openjdk-21-jre-headless
sudo useradd --system --home /srv/minecraft --shell /usr/sbin/nologin minecraft
sudo install -d -o minecraft -g minecraft /srv/minecraft && cd /srv/minecraft
# Fabric server launcher for MC 1.21.1 / loader 0.16.14. Take the current installer
# version from fabricmc.net/use/server and substitute it for INSTALLER.
sudo -u minecraft curl -fLo fabric-server.jar \
  https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.14/INSTALLER/server/jar
sudo -u minecraft mkdir mods   # optional: fabric-api.jar for 1.21.1
echo 'eula=true' | sudo -u minecraft tee eula.txt
```

Key `server.properties` values (the rest are defaults):

```properties
online-mode=true
enforce-secure-profile=true
white-list=true
enforce-whitelist=true
max-players=2
view-distance=8
simulation-distance=4
difficulty=normal
gamemode=survival
force-gamemode=true
spawn-protection=0
player-idle-timeout=0
enable-rcon=false
level-name=world
```

Raise `max-players` for more humans. Allowlist and op from the console (below):
`whitelist add <agent player>`, `whitelist add <your name>`, `op <your name>`.

### systemd unit and console pipe

The server reads its console from a named pipe so operators can send commands
without RCON. `/srv/minecraft/start-with-console.sh`:

```bash
set -eu
exec 3<>/srv/minecraft/console
exec /usr/bin/java -Xms1G -Xmx2G -jar fabric-server.jar nogui <&3
```

Create the pipe once: `sudo -u minecraft mkfifo -m 600 /srv/minecraft/console`.

`/etc/systemd/system/musecraft-server.service`:

```ini
[Unit]
After=network-online.target
Wants=network-online.target
[Service]
User=minecraft
Group=minecraft
WorkingDirectory=/srv/minecraft
ExecStart=/bin/bash /srv/minecraft/start-with-console.sh
Restart=on-failure
TimeoutStopSec=90
KillSignal=SIGINT
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/srv/minecraft
StandardOutput=journal+console
StandardError=journal+console
[Install]
WantedBy=multi-user.target
```

`sudo systemctl enable --now musecraft-server`. Logs: `journalctl -u musecraft-server`.

## Operating it

Send a console command (write to the pipe as root):

```bash
echo 'say hello' | sudo tee /srv/minecraft/console >/dev/null
```

- **Start:** `gcloud compute instances start $NAME --project $PROJECT --zone $ZONE`.
  The unit starts the server on boot; the player's session reconnects on its own
  within about two minutes.
- **Stop cleanly:** send `save-all flush`, then `stop` through the pipe, wait
  for the process to exit, then `gcloud compute instances stop ...`.
  `systemctl stop` sends SIGINT and exits without the save log, so save first.
- **Runtime limit:** the instance stops itself 12 h after each start
  (`maxRunDuration`). Start it again when you need it; there is no renewal job.
- **After a long client run:** if the player's session reports `Invalid session`
  after a restart, the VM client's Microsoft token is stale. Have the player rerun
  `musecraft client`, then `musecraft play`.
- **Backups:** the world lives in `/srv/minecraft/<level-name>`. Copy it with
  the server stopped or right after `save-all flush`.
- **World tuning for tests or recordings:** `gamerule doMobSpawning false`,
  `gamerule randomTickSpeed 0` (stops crops growing), `gamerule doDaylightCycle
  false`, `time set`, `weather clear`. Restore the defaults afterwards.
