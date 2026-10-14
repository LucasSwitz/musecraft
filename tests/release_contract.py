#!/usr/bin/env python3
"""Exercise actual release consumption, corruption rejection and update preservation."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
archive = Path(sys.argv[1]).resolve()
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
with tempfile.TemporaryDirectory(prefix='musecraft-release-test-') as scratch:
    skills = Path(scratch) / 'skills'
    command = [sys.executable, str(root / 'scripts/install_skill.py'), str(archive), '--sha256', digest, '--skills-dir', str(skills)]
    subprocess.run(command, check=True)
    installed = skills / 'musecraft'
    manifest = json.loads((installed / 'manifest.json').read_text())
    assert (installed / manifest['jar']).is_file()
    assert not (installed / 'client-mod').exists()
    verifier = installed / 'scripts/verify_release.py'
    subprocess.run([sys.executable, str(verifier), str(installed)], check=True)
    original = (installed / 'scripts/adapter.cjs').read_bytes()
    (installed / 'scripts/adapter.cjs').write_bytes(original + b'\n// damaged payload\n')
    assert subprocess.run([sys.executable, str(verifier), str(installed)], capture_output=True).returncode != 0
    # Bad outer checksum must not replace an existing skill.
    bad = command.copy(); bad[bad.index('--sha256') + 1] = '0' * 64
    assert subprocess.run(bad, capture_output=True).returncode != 0
    assert (installed / 'scripts/adapter.cjs').read_bytes() != original
    # A valid replacement preserves the prior tree for rollback.
    subprocess.run(command, check=True)
    assert (installed / 'scripts/adapter.cjs').read_bytes() == original
    assert (skills / '.musecraft.previous/scripts/adapter.cjs').read_bytes() != original
    assert subprocess.run(command, capture_output=True).returncode != 0
print('PASS: verified install, corrupted payload rejection, bad checksum preservation, atomic update with rollback copy, backup collision rejection')
