#!/usr/bin/env python3
"""Consume a checksummed release into a Muse user-skill directory atomically."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import tarfile
import tempfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('archive', type=Path)
    p.add_argument('--sha256', required=True, help='Trusted release checksum')
    p.add_argument('--skills-dir', type=Path, required=True)
    args = p.parse_args()
    if hashlib.sha256(args.archive.read_bytes()).hexdigest() != args.sha256:
        raise SystemExit('Archive checksum mismatch')
    args.skills_dir.mkdir(parents=True, exist_ok=True)
    if not os.access(args.skills_dir, os.W_OK):
        raise SystemExit('Skill directory is not writable. On a Muse VM, have the host operator install this release; do not relax the skills directory permissions.')
    target = args.skills_dir / 'musecraft'
    # Dot-prefixed so skill discovery skips it: a second folder declaring the
    # same skill name would shadow the new install.
    backup = args.skills_dir / '.musecraft.previous'
    if backup.exists() or backup.is_symlink():
        raise SystemExit(f'Previous backup exists: {backup}; archive it before another update')
    with tempfile.TemporaryDirectory(prefix='.musecraft-stage-', dir=args.skills_dir) as scratch:
        with tarfile.open(args.archive, 'r:gz') as tar:
            for member in tar.getmembers():
                parts = Path(member.name).parts
                if not parts or parts[0] != 'musecraft' or '..' in parts or not (member.isfile() or member.isdir()):
                    raise SystemExit('Unexpected member in release archive')
            tar.extractall(scratch, filter='data')
        staged = Path(scratch) / 'musecraft'
        spec = importlib.util.spec_from_file_location('verify_release', staged / 'scripts/verify_release.py')
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        manifest = verifier.verify(staged)
        if target.exists() or target.is_symlink():
            target.rename(backup)
        try:
            staged.rename(target)
        except BaseException:
            if backup.exists() or backup.is_symlink():
                backup.rename(target)
            raise
    print(f"Installed skill {manifest['release']['version']} at {target}")
    print('With Minecraft and controller stopped, run its install.sh to update the client mod and controller together.')


if __name__ == '__main__':
    main()
