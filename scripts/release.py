#!/usr/bin/env python3
"""Build the mod and a self-contained skill release; never package game/account data."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', default=os.environ.get('GRADLE', 'gradle'))
    parser.add_argument('--offline', action='store_true', help='Use an already populated Gradle cache')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    release = json.loads((ROOT / 'release.json').read_text())
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all'], cwd=ROOT, text=True).strip():
        raise SystemExit('Commit the source before packaging: every release must identify its exact source revision.')
    subprocess.run([args.gradle, '-p', str(ROOT / 'mod'), 'build', '--no-daemon', '--max-workers=2',
                    *(['--offline'] if args.offline else [])], check=True)
    args.output.mkdir(parents=True, exist_ok=True)
    stem = f"musecraft-{release['version']}"
    archive = args.output / f'{stem}.tar.gz'
    with tempfile.TemporaryDirectory() as scratch:
        package = Path(scratch) / 'musecraft'
        shutil.copytree(ROOT / 'skill', package, ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'assets', 'release.json', 'manifest.json'))
        (package / 'assets').mkdir()
        jar = f"musecraft-client-{release['version']}.jar"
        shutil.copyfile(ROOT / 'mod/build/libs' / jar, package / 'assets' / jar)
        shutil.copyfile(ROOT / 'LICENSE', package / 'LICENSE')
        (package / 'release.json').write_text(json.dumps(release, indent=2) + '\n')
        files = {str(p.relative_to(package)): digest(p) for p in sorted(package.rglob('*')) if p.is_file()}
        source_files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
        manifest = {'release': release, 'revision': revision, 'jar': 'assets/' + jar, 'files': files,
                    'sources': {p: digest(ROOT / p) for p in source_files if p}}
        (package / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
        # Stable archive metadata makes repeat builds comparable.
        def canonical(info):
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ''
            info.mode = 0o755 if info.isdir() or info.mode & 0o111 else 0o644
            return info
        with archive.open('wb') as output, gzip.GzipFile(fileobj=output, mode='wb', filename='', mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w') as tar:
                tar.add(package, arcname='musecraft', filter=canonical)
    checksum = digest(archive)
    archive.with_suffix(archive.suffix + '.sha256').write_text(f'{checksum}  {archive.name}\n')
    print(json.dumps({'archive': str(archive), 'sha256': checksum, 'revision': revision, 'version': release['version']}))


if __name__ == '__main__':
    main()
