#!/usr/bin/env python3
"""Verify the unpacked release before installation or network downloads."""
import hashlib
import json
from pathlib import Path
import sys


def verify(root):
    root = root.resolve()
    manifest_path = root / 'manifest.json'
    if not manifest_path.is_file():
        raise ValueError('Use a built Musecraft release. In a source checkout, run python3 scripts/release.py first.')
    manifest = json.loads(manifest_path.read_text())
    release = json.loads((root / 'release.json').read_text())
    if manifest['release'] != release:
        raise ValueError('Release metadata does not match manifest')
    actual = {str(p.relative_to(root)) for p in root.rglob('*') if p.is_file() and p != manifest_path and '__pycache__' not in p.parts and p.suffix != '.pyc'}
    if actual != set(manifest['files']):
        raise ValueError('Release file inventory mismatch; extract a fresh complete archive')
    for rel, expected in manifest['files'].items():
        path = root / rel
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError(f'Invalid release path: {rel}')
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Release checksum mismatch: {rel}')
    if manifest['jar'] not in manifest['files']:
        raise ValueError('Mod is missing from release manifest')
    return manifest


if __name__ == '__main__':
    try:
        print(json.dumps(verify(Path(sys.argv[1]))['release']))
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(str(error))
