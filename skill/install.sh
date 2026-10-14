#!/usr/bin/env bash
# Install Musecraft around a separately installed, licensed Minecraft client.
set -euo pipefail
umask 077
skill_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
data_dir="${MUSECRAFT_HOME:-${HOME:+$HOME/workspace/musecraft}}"
game_dir=
prism_instance=
setup_prism=false
vm_performance=false
prism_data=
systemd_scope=
usage() {
  cat <<'HELP'
Usage: ./install.sh --setup-prism [--home /absolute/directory] [--prism-data /absolute/prism-data] [--systemd-scope user|system] [--vm-performance]
       ./install.sh (--minecraft-dir /absolute/game/directory | --prism-instance /absolute/instance/directory) [--home /absolute/musecraft/directory] [--systemd-scope user|system] [--vm-performance]

Use --setup-prism to download and configure Prism, Java 21, Minecraft 1.21.1,
Fabric and Musecraft automatically. No game files are shipped by this skill.
Sign in with your licensed account in Prism afterward. Close Prism before setup.

The existing-client mode below is Linux x86_64. Requires Python 3, curl, tar,
unzip, sha256sum, flock, and systemd. The official-launcher path also requires JDK 21. First install and run Minecraft Java 1.21.1 through
its official launcher or Prism using your own licensed account. For Prism,
install Fabric Loader 0.16.14 through Edit Instance first. See setup.md.

Downloads verified Node 25.9.0, installs our checksummed mod, installs the
Figura 0.1.6 player-model mod with the bundled Jolly avatar, and
creates a controller launcher. The official-launcher path also downloads
Fabric installer 1.0.3 and installs Fabric Loader 0.16.14; Prism owns its loader.
Existing-client mode does not install Minecraft, accept
its EULA, sign in, create a server, or change your account/profile files.
Use --vm-performance for a reversible 854x480, 30 FPS, 6-chunk preset on small VMs.
Developers build releases from the source repository with scripts/release.py.
Does not start services or launch the game. Rerun after closing the game
and stopping the Musecraft controller to update this installation.
HELP
}
while (($#)); do
  case "$1" in
    --minecraft-dir|--prism-instance|--home|--systemd-scope|--prism-data)
      (($# >= 2)) || { usage >&2; exit 2; }
      case "$1" in
        --home) data_dir=$2 ;;
        --prism-data) prism_data=$2 ;;
        --minecraft-dir) game_dir=$2 ;;
        --prism-instance) prism_instance=$2 ;;
        --systemd-scope) systemd_scope=$2 ;;
      esac
      shift 2 ;;
    --build-mod) echo 'Build in the source repo with python3 scripts/release.py, then install the resulting archive.' >&2; exit 2 ;;
    --setup-prism) setup_prism=true; shift ;;
    --vm-performance) vm_performance=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
python3 "$skill_dir/scripts/verify_release.py" "$skill_dir"
if $setup_prism; then
  [[ -z "$game_dir" && -z "$prism_instance" ]] || { echo '--setup-prism cannot be combined with an existing-client option' >&2; exit 2; }
  if [[ -z "$systemd_scope" ]]; then
    systemd_scope=user
    ((EUID != 0)) || systemd_scope=system
  fi
  args=(--home "$data_dir" --systemd-scope "$systemd_scope")
  [[ -z "$prism_data" ]] || args+=(--prism-data "$prism_data")
  $vm_performance && args+=(--vm-performance)
  exec python3 "$skill_dir/scripts/prepare_prism.py" "${args[@]}"
fi
[[ -z "$prism_data" ]] || { echo '--prism-data requires --setup-prism' >&2; exit 2; }
if [[ -n "$prism_instance" ]]; then
  [[ "$prism_instance" == /* && -z "$game_dir" ]] || { echo 'Use one absolute --minecraft-dir or --prism-instance, not both' >&2; exit 2; }
  python3 - "$prism_instance" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])
try:
    components=json.loads((p/'mmc-pack.json').read_text())['components']
    versions={c['uid']:c.get('version') for c in components}
except (OSError,ValueError,KeyError,TypeError):
    raise SystemExit('Invalid Prism instance: mmc-pack.json is missing or invalid')
if versions.get('net.minecraft')!='1.21.1' or versions.get('net.fabricmc.fabric-loader')!='0.16.14':
    raise SystemExit('In Prism, create Minecraft 1.21.1 and install Fabric Loader 0.16.14 before installing Musecraft')
if not (p/'minecraft').is_dir():
    raise SystemExit('Launch this Prism instance once, then quit the game before installing Musecraft')
PY
  game_dir="$prism_instance/minecraft"
fi
[[ -z "$systemd_scope" || "$systemd_scope" == user || "$systemd_scope" == system ]] || { echo 'systemd scope must be user or system' >&2; exit 2; }
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Supported platform: Linux x86_64' >&2; exit 1; }
[[ "$data_dir" == /* && "$game_dir" == /* ]] || { echo 'Pass absolute --home and --minecraft-dir paths' >&2; exit 2; }
for cmd in python3 curl tar unzip sha256sum flock systemctl systemd-run; do
  command -v "$cmd" >/dev/null || { echo "Missing prerequisite: $cmd. See setup.md." >&2; exit 1; }
done
if [[ -z "$prism_instance" ]]; then
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="$JAVA_HOME/bin/java"
  javac_bin="$JAVA_HOME/bin/javac"
else
  java_bin=$(command -v java || true)
  javac_bin=$(command -v javac || true)
fi
[[ -x "$java_bin" && -x "$javac_bin" ]] || { echo 'Install a full JDK 21 and set JAVA_HOME' >&2; exit 1; }
[[ $("$javac_bin" -version 2>&1) == 'javac 21'* ]] || { echo 'JDK 21 is required' >&2; exit 1; }
export JAVA_HOME
JAVA_HOME=$(dirname -- "$(dirname -- "$(readlink -f -- "$javac_bin")")")
fi
[[ -n "$prism_instance" || ( -f "$game_dir/versions/1.21.1/1.21.1.json" && -f "$game_dir/versions/1.21.1/1.21.1.jar" ) ]] || {
  echo 'Run official Minecraft Java 1.21.1 once in this launcher directory, then close it. See setup.md.' >&2; exit 1;
}
# Do not silently convert the existing standalone development world.
[[ ! -e "$data_dir/server/server.properties" && ! -e "$data_dir/venv" ]] || {
  echo 'This is an existing standalone test installation. Choose a different --home.' >&2; exit 1;
}
mkdir -p -- "$data_dir"
exec 9>"$data_dir/install.lock"
flock -n 9 || { echo 'Another Musecraft install is running' >&2; exit 1; }
python3 "$skill_dir/scripts/install_state.py" "$data_dir"
mkdir -p -- "$data_dir/downloads" "$data_dir/toolchain" "$data_dir/bin" "$game_dir/mods"
fetch() { curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 600 "$1" -o "$2"; }
verified() {
  local url=$1 name=$2 digest=$3 file="$data_dir/downloads/$2"
  [[ "$digest" =~ ^[a-fA-F0-9]{64}$ ]] || { echo "Invalid publisher checksum for $name" >&2; exit 1; }
  if [[ ! -f "$file" ]] || [[ $(sha256sum "$file" | cut -d' ' -f1) != "$digest" ]]; then
    fetch "$url" "$file.part"
    printf '%s  %s\n' "$digest" "$file.part" | sha256sum -c -
    mv -- "$file.part" "$file"
  fi
}
node_name=node-v25.9.0-linux-x64.tar.xz
fetch https://nodejs.org/dist/v25.9.0/SHASUMS256.txt "$data_dir/downloads/node-shasums.txt"
node_sha=$(awk -v n="$node_name" '$2 == n {print $1}' "$data_dir/downloads/node-shasums.txt")
verified "https://nodejs.org/dist/v25.9.0/$node_name" "$node_name" "$node_sha"
if [[ ! -x "$data_dir/toolchain/node-v25.9.0-linux-x64/bin/node" ]]; then
  tar --no-same-owner -xJf "$data_dir/downloads/$node_name" -C "$data_dir/toolchain"
fi
[[ ! -e "$data_dir/toolchain/node" || -L "$data_dir/toolchain/node" ]] || { echo 'toolchain/node already exists as a directory; choose a fresh --home' >&2; exit 1; }
ln -sfn node-v25.9.0-linux-x64 "$data_dir/toolchain/node"
if [[ -z "$prism_instance" ]]; then
fabric_url=https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.3/fabric-installer-1.0.3.jar
fetch "$fabric_url.sha256" "$data_dir/downloads/fabric.sha256"
verified "$fabric_url" fabric-installer-1.0.3.jar "$(tr -d '\r\n' < "$data_dir/downloads/fabric.sha256")"
"$java_bin" -jar "$data_dir/downloads/fabric-installer-1.0.3.jar" client -dir "$game_dir" -mcversion 1.21.1 -loader 0.16.14 -noprofile
fi
python3 - "$skill_dir" "$game_dir" <<'PYMOD'
import json,os,shutil,sys,zipfile
from pathlib import Path
skill=Path(sys.argv[1]); meta=json.loads((skill/'manifest.json').read_text())
mods=Path(sys.argv[2])/'mods'
source=skill/meta['jar']; destination=mods/source.name
# Identify our mod by its Fabric id, not by an overbroad filename pattern.
previous=[]
for jar in mods.glob('*.jar'):
    try:
        with zipfile.ZipFile(jar) as archive:
            if json.loads(archive.read('fabric.mod.json'))['id']=='musecraft': previous.append(jar)
    except (OSError,ValueError,KeyError,zipfile.BadZipFile): pass
staged=destination.with_suffix('.jar.pending')
shutil.copyfile(source,staged)
os.replace(staged,destination)
for jar in previous:
    if jar!=destination: jar.unlink()
PYMOD
# Player model: Figura draws the Muse avatar. Install the verified mod and the
# bundled avatars; uploading one for an account is a separate, explicit step.
figura_name=figura-0.1.6+1.21.1-fabric-mc.jar
verified "https://cdn.modrinth.com/data/s9gIPDom/versions/ugF3NunW/figura-0.1.6%2B1.21.1-fabric-mc.jar" "$figura_name" \
  7b1278cadb52507abf6645955dcf3f92ec34bf9ea4a9a665c6a98423e2668628
python3 - "$data_dir/downloads/$figura_name" "$game_dir" "$skill_dir/avatars" <<'PYFIGURA'
import json,os,shutil,sys,zipfile
from pathlib import Path
source,game,avatars=Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3])
mods=game/'mods'; destination=mods/source.name
previous=[]
for jar in mods.glob('*.jar'):
    try:
        with zipfile.ZipFile(jar) as archive:
            if json.loads(archive.read('fabric.mod.json'))['id']=='figura': previous.append(jar)
    except (OSError,ValueError,KeyError,zipfile.BadZipFile): pass
staged=destination.with_suffix('.jar.pending')
shutil.copyfile(source,staged)
os.replace(staged,destination)
for jar in previous:
    if jar!=destination: jar.unlink()
# Replace only the bundled avatars' own folders; other avatars stay.
for avatar in sorted(p for p in avatars.iterdir() if p.is_dir()):
    target=game/'figura'/'avatars'/avatar.name
    staged=target.with_name('.'+avatar.name+'.pending')
    shutil.rmtree(staged,ignore_errors=True)
    shutil.copytree(avatar,staged)
    if target.exists(): shutil.rmtree(target)
    os.replace(staged,target)
PYFIGURA
python3 - "$data_dir" "$game_dir" "$systemd_scope" <<'PY'
import json,os,sys
from pathlib import Path
root=Path(sys.argv[1])
config={'mode':'external-client','systemdScope':sys.argv[3] or ('system' if os.getuid()==0 else 'user'),'minecraftDirectory':sys.argv[2]}
tmp=root/'install.json.tmp';tmp.write_text(json.dumps(config,indent=2)+'\n');tmp.replace(root/'install.json')
PY
{
  echo '#!/usr/bin/env bash'
  printf 'export MUSECRAFT_HOME=%q\n' "$data_dir"
  printf 'exec %q "$@"\n' "$skill_dir/scripts/musecraft"
} > "$data_dir/bin/musecraft"
chmod 700 "$data_dir/bin/musecraft"
if $vm_performance; then python3 "$skill_dir/scripts/configure_game.py" "$game_dir"; fi
if [[ -n "$prism_instance" ]]; then
  printf '\nInstalled into Prism instance: %s\nIn Edit Instance → Settings → Java, enable Java arguments override and add:\n-Dmusecraft.home="%s"\n' "$prism_instance" "$data_dir"
else
  printf '\nInstalled. In the official launcher select fabric-loader-0.16.14-1.21.1,\nuse this game directory: %s\nand add this JVM argument: "-Dmusecraft.home=%s"\n' "$game_dir" "$data_dir"
fi
printf 'Then sign in, launch, and run: %q play server=HOST:25565 player=PLAYER owner=OWNER\nSee %s/setup.md for launch, server, and verification steps.\n' "$data_dir/bin/musecraft" "$skill_dir"
