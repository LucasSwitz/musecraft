#!/usr/bin/env python3
"""Give Qt the managed public CA bundle in a private mount, then launch Prism."""
import os
from pathlib import Path
import re
import shutil
import signal
import ssl
import subprocess
import sys
import tempfile


def main():
    command = sys.argv[1:]
    if command[:1] == ['--mount']:
        # Re-entered in a private user/mount namespace, with no host privileges.
        directory, command = command[1], command[2:]
        subprocess.run(['mount', '--bind', directory, '/etc/ssl/certs'], check=True)
        subprocess.run(['mount', '-o', 'remount,bind,ro', '/etc/ssl/certs'], check=True)
        os.execvp(command[0], command)
    if not command:
        raise SystemExit('Usage: launch_prism.py COMMAND [ARGS...]')
    bundle = os.environ.get('SSL_CERT_FILE')
    if not sys.platform.startswith('linux') or not bundle:
        os.execvp(command[0], command)
    # Qt 6.10 searches fixed directories rather than SSL_CERT_FILE/SSL_CERT_DIR.
    # Preserve system roots and expose the caller's existing trust only to this
    # process tree. Never disable verification or install a host-wide authority.
    data = Path(bundle).read_bytes()
    certificates = re.findall(b'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', data, re.S)
    if not certificates:
        raise SystemExit('SSL_CERT_FILE contains no certificates')
    ssl.create_default_context(cafile=bundle)
    with tempfile.TemporaryDirectory(prefix='musecraft-prism-ca-') as directory:
        root = Path(directory)
        shutil.copytree('/etc/ssl/certs', root, symlinks=True, dirs_exist_ok=True)
        for index, certificate in enumerate(certificates):
            (root / f'musecraft-managed-{index}.pem').write_bytes(certificate + b'\n')
        rehash = subprocess.run(['openssl', 'rehash', directory], capture_output=True, text=True)
        if rehash.returncode:
            raise SystemExit('Could not prepare Qt trust directory: ' + rehash.stderr)
        child = subprocess.Popen([
            'unshare', '--user', '--map-root-user', '--mount', '--propagation', 'private',
            sys.executable, str(Path(__file__).resolve()), '--mount', directory, *command], start_new_session=True)
        def forward(signum, _frame):
            if child.poll() is None:
                try:
                    os.killpg(child.pid, signum)
                except ProcessLookupError:
                    pass
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, forward)
        code = child.wait()
        if code:
            raise SystemExit(code if code > 0 else 128 - code)


if __name__ == '__main__':
    main()
