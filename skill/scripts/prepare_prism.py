#!/usr/bin/env python3
"""Prepare a licensed Prism client without handling account credentials."""
import argparse
import concurrent.futures
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
from urllib.parse import urlparse
from install_state import ensure_stopped

PRISM_VERSION = '11.1.0'
PRISM_SHA = '07d0987fd46b3ec442c15b25b6ab78b29737d33a8095039f488f301103bca463'
RELEASE = json.loads((Path(__file__).resolve().parents[1] / 'release.json').read_text())
COMPONENTS = {'org.lwjgl3': '3.3.3', 'net.minecraft': RELEASE['minecraft'],
              'net.fabricmc.intermediary': RELEASE['minecraft'], 'net.fabricmc.fabric-loader': RELEASE['fabricLoader']}
META = 'https://meta.prismlauncher.org/v1/'


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as f:
        tmp = Path(f.name)
        f.write(data)
    tmp.replace(path)


def fetch(url, path, digest=None, algorithm='sha256', size=None):
    if urlparse(url).scheme != 'https':
        raise ValueError('Download must use HTTPS')
    if digest and not re.fullmatch('[a-fA-F0-9]{' + str(hashlib.new(algorithm).digest_size * 2) + '}', digest):
        raise ValueError('Invalid publisher checksum')

    def valid(p):
        if not p.is_file() or (size is not None and p.stat().st_size != size):
            return False
        if not digest:
            return False
        with p.open('rb') as stream:
            return hashlib.file_digest(stream, algorithm).hexdigest() == digest.lower()

    if valid(path):
        return path.read_bytes()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.download')
    command = ['curl', '--fail', '--location', '--silent', '--show-error',
               '--proto', '=https', '--proto-redir', '=https', '--retry', '4',
               '--retry-all-errors', '--connect-timeout', '15', '--max-time', '600',
               '--output', str(tmp), url]
    with subprocess.Popen(command) as process:
        while True:
            try:
                code = process.wait(timeout=15)
                break
            except subprocess.TimeoutExpired:
                mb = tmp.stat().st_size / 1048576 if tmp.exists() else 0
                print(f'Downloading {path.name}: {mb:.1f} MiB received', flush=True)
        if code:
            raise subprocess.CalledProcessError(code, command)
    if digest and not valid(tmp):
        tmp.unlink(missing_ok=True)
        raise ValueError('Publisher checksum/size mismatch for ' + path.name)
    tmp.replace(path)
    return path.read_bytes()


def linux_library(lib):
    rules = lib.get('rules', [])
    allowed = not rules
    for rule in rules:
        os_rule = rule.get('os', {})
        if os_rule.get('name', 'linux') != 'linux':
            continue
        if os_rule.get('arch', 'x86_64') not in ('x86_64', 'amd64'):
            continue
        if os_rule.get('version') and not re.search(os_rule['version'], platform.release()):
            continue
        if rule.get('features'):
            continue
        allowed = rule['action'] == 'allow'
    return allowed


def maven_path(name):
    parts = name.split(':')
    if len(parts) not in (3, 4) or any('/' in p or '\\' in p or p in ('', '.', '..') for p in parts):
        raise ValueError('Invalid library coordinate')
    group, artifact, version = parts[:3]
    classifier = '-' + parts[3] if len(parts) == 4 else ''
    return f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}{classifier}.jar'


def prepare(root, data, scope, vm_performance=False):
    skill = Path(__file__).resolve().parent.parent
    downloads = root / 'downloads'
    toolchain = root / 'toolchain'
    instance = data / 'instances/Musecraft'
    marker = instance / '.musecraft-managed'
    if instance.exists() and not marker.exists():
        raise ValueError('An unrelated Musecraft instance already exists. Choose another --prism-data directory.')
    atomic(marker, b'Managed by Musecraft setup\n')
    (root / 'setup-result.json').unlink(missing_ok=True)
    print('[1/6] Downloading and verifying Prism Launcher and Java 21', flush=True)
    app = downloads / 'PrismLauncher.AppImage'
    fetch(f'https://github.com/PrismLauncher/PrismLauncher/releases/download/{PRISM_VERSION}/PrismLauncher-Linux-x86_64.AppImage', app, PRISM_SHA)
    launcher = toolchain / 'prism'
    if not (launcher / 'AppRun').is_file():
        toolchain.mkdir(parents=True, exist_ok=True)
        app.chmod(0o700)
        with tempfile.TemporaryDirectory(dir=toolchain) as temp:
            subprocess.run([str(app), '--appimage-extract'], cwd=temp, stdout=subprocess.DEVNULL, check=True)
            Path(temp, 'squashfs-root').rename(launcher)
    java_manifest = downloads / 'java-runtime.json'
    # Keep the resolved publisher version stable across interrupted retries.
    if not java_manifest.exists():
        fetch('https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=linux&vendor=eclipse', java_manifest)
    package = json.loads(java_manifest.read_text())[0]['binary']['package']
    archive = downloads / 'java21.tar.gz'
    fetch(package['link'], archive, package['checksum'], size=package['size'])
    java = toolchain / 'java21'
    if not (java / 'bin/java').exists():
        with tempfile.TemporaryDirectory(dir=toolchain) as temp:
            with tarfile.open(archive) as tar:
                tar.extractall(temp, filter='data')
            children = list(Path(temp).iterdir())
            if len(children) != 1 or not (children[0] / 'bin/java').is_file():
                raise ValueError('Unexpected Java runtime archive layout')
            children[0].rename(java)

    print('[2/6] Preparing pinned Minecraft/Fabric components from publisher metadata', flush=True)
    # Prism's supported custom-component files avoid Qt metadata refresh during
    # launch. These are exact, hash-verified publisher documents, not a mirror
    # server, modified launcher, or changed authentication endpoint.
    index = json.loads(fetch(META + 'index.json', downloads / 'prism-index.json'))
    packages = {p['uid']: p for p in index['packages']}
    docs = {}
    for uid, version in COMPONENTS.items():
        raw = fetch(META + uid + '/index.json', downloads / f'{uid}-index.json', packages[uid]['sha256'])
        entry = next(v for v in json.loads(raw)['versions'] if v['version'] == version)
        raw = fetch(META + uid + '/' + version + '.json', downloads / f'{uid}-{version}.json', entry['sha256'])
        docs[uid] = json.loads(raw)
        atomic(instance / 'patches' / (uid + '.json'), raw)
    atomic(marker, b'Managed by Musecraft setup\n')
    atomic(instance / 'mmc-pack.json', json.dumps({'formatVersion': 1, 'components': [
        {'uid': uid, 'version': v, 'cachedName': docs[uid]['name'], 'cachedVersion': v,
         'important': uid == 'net.minecraft'} for uid, v in COMPONENTS.items()]}).encode())
    (instance / 'minecraft').mkdir(exist_ok=True)

    print('[3/6] Downloading and verifying Minecraft client and libraries', flush=True)
    libraries = {}
    for doc in docs.values():
        for lib in doc.get('libraries', []) + ([doc['mainJar']] if 'mainJar' in doc else []):
            if not linux_library(lib):
                continue
            rel = maven_path(lib['name'])
            artifact = lib.get('downloads', {}).get('artifact', {})
            url = artifact.get('url') or lib.get('url', 'https://libraries.minecraft.net').rstrip('/') + '/' + rel
            libraries[rel] = (url, artifact)

    def download_library(item):
        rel, (url, artifact) = item
        digest = artifact.get('sha1')
        if not digest:
            digest = fetch(url + '.sha1', downloads / (hashlib.sha256(url.encode()).hexdigest() + '.sha1')).decode().split()[0]
        fetch(url, data / 'libraries' / rel, digest, 'sha1', artifact.get('size'))

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(download_library, libraries.items()))
    print(f'Verified {len(libraries)} client/library files', flush=True)
    print('[4/6] Downloading and verifying game assets (resumes verified files)', flush=True)
    subprocess.run([sys.executable, str(skill / 'scripts/fetch_prism_assets.py'), '--assets-dir', str(data / 'assets')], check=True)

    print('[5/6] Installing the verified Musecraft mod and controller', flush=True)
    env = dict(os.environ, JAVA_HOME=str(java))
    with (root / 'install.log').open('w') as log:
        result = subprocess.run([str(skill / 'install.sh'), '--prism-instance', str(instance), '--home', str(root),
                                 '--systemd-scope', scope, *(['--vm-performance'] if vm_performance else [])], env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise ValueError('Mod/controller installation failed; see ' + str(root / 'install.log'))
    config = data / 'prismlauncher.cfg'
    if not config.exists():
        atomic(config, ('[General]\nLanguage=en_US\nProxyType=Default\n'
            'IgnoreJavaWizard=true\nAutomaticJavaDownload=false\nAutomaticJavaSwitch=false\n'
            'UserAskedAboutAutomaticJavaDownload=true\nApplicationTheme=system\nIconTheme=pe_colored\n'
            'JavaPath=' + str(java / 'bin/java') + '\n').encode())
    # Avoid QSettings/JVM parsing ambiguity for spaces by generating a stable
    # no-space property path. Never replace user files to create this alias.
    alias = Path('/tmp') / ('musecraft-home-' + hashlib.sha256(str(root).encode()).hexdigest()[:24])
    if alias.is_symlink():
        if alias.resolve() != root.resolve():
            raise ValueError('Controller path alias belongs to another installation')
    elif alias.exists():
        raise ValueError('Controller path alias already exists')
    else:
        alias.symlink_to(root, target_is_directory=True)
    atomic(instance / 'instance.cfg', ('[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=Musecraft\n'
           'AutomaticJava=false\nOverrideJavaLocation=true\nJavaPath=' + str(java / 'bin/java') + '\n'
           'OverrideJavaArgs=true\nJvmArgs=-Dmusecraft.home=' + str(alias) + '\n'
           'OverrideMemory=true\nMinMemAlloc=512\nMaxMemAlloc=2048\n'
           # Headless launches cannot answer Prism's low-free-RAM modal.
           'LowMemWarning=false\n').encode())
    launch = root / 'bin/launch-minecraft'
    atomic(launch, ('#!/usr/bin/env bash\nset -euo pipefail\n'
        ': "${DISPLAY:?Set DISPLAY to your visible desktop before launching}"\n'
        'if [[ -e /run/hatch/egress-tls/ca-bundle.pem && -z "${HTTPS_PROXY:-${https_proxy:-}}" ]]; then\n'
        '  echo "On Muse, launch through a fresh managed exec session." >&2; exit 1\nfi\n'
        '# Restore our non-secret path alias after a reboot.\n'
        'if [[ ! -e ' + shlex.quote(str(alias)) + ' && ! -L ' + shlex.quote(str(alias)) + ' ]]; then\n'
        '  ln -s -- ' + shlex.quote(str(root)) + ' ' + shlex.quote(str(alias)) + '\nfi\n'
        'export LC_ALL=C.UTF-8\n'
        '# MUSECRAFT_PRISM_UI=1 opens Prism without the game, for signing in.\n'
        'launch=(--launch Musecraft); [[ "${MUSECRAFT_PRISM_UI:-}" == 1 ]] && launch=()\n'
        'exec python3 ' + shlex.quote(str(skill / 'scripts/launch_prism.py')) + ' dbus-run-session -- ' + shlex.quote(str(launcher / 'AppRun')) + ' -d ' + shlex.quote(str(data)) + ' "${launch[@]}" "$@"\n').encode())
    launch.chmod(0o700)
    atomic(root / 'setup-result.json', json.dumps({'prepared': True, 'accountVerified': False,
        'prismData': str(data), 'instance': str(instance), 'launcher': str(launch),
        'components': COMPONENTS, 'libraryFilesVerified': len(libraries)}, indent=2).encode())
    print('[6/6] Setup complete. Launch on your desktop:', flush=True)
    print(shlex.quote(str(launch)))
    print('In Prism: Accounts → Manage Accounts → Add Microsoft. Complete sign-in in your browser, then launch Musecraft.')
    print('No password or token is needed by this installer. Then join a server with:')
    print(shlex.quote(str(root / 'bin/musecraft')) + ' play server=HOST:25565 player=PLAYER owner=OWNER')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', required=True, type=Path)
    parser.add_argument('--prism-data', type=Path)
    parser.add_argument('--systemd-scope', choices=['user', 'system'], default='user')
    parser.add_argument('--vm-performance', action='store_true')
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        parser.error('Python 3.12 or newer is required')
    root = args.home
    data = args.prism_data or root / 'prism-data'
    if not root.is_absolute() or not data.is_absolute():
        parser.error('Use absolute directory paths')
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Supported platform: Linux x86_64')
    for name in ['curl', 'tar', 'unzip', 'sha256sum', 'flock', 'systemctl', 'systemd-run', 'dbus-run-session', 'openssl', 'unshare', 'mount']:
        if not shutil.which(name):
            parser.error(f'Missing {name}; see setup.md prerequisites')
    if (root / 'server/server.properties').exists():
        parser.error('Choose a new --home; this is a standalone development installation')
    for proc in Path('/proc').glob('[0-9]*/comm'):
        try:
            if proc.stat().st_uid == os.getuid() and proc.read_text().strip().lower() == 'prismlauncher':
                parser.error('Quit Prism before running setup; it can overwrite instance settings on exit')
        except (OSError, ProcessLookupError):
            pass
    root.mkdir(parents=True, exist_ok=True)
    with (root / 'prepare.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            ensure_stopped(root)
            prepare(root, data, args.systemd_scope, args.vm_performance)
        except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, StopIteration, KeyError) as error:
            print(f'Setup stopped: {error}. Fix the reported issue and rerun the same command; verified downloads are retained.', file=sys.stderr)
            return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
