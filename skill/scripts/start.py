#!/usr/bin/env python3
"""Recreate the workload's guest systemd unit after a fresh VM image boot."""
import fcntl
import json
import os
from pathlib import Path
import subprocess
import socket
import time
import sys

root=Path(os.environ.get('MUSECRAFT_HOME','/home/hatch/workspace/musecraft'))
root.mkdir(exist_ok=True)
config_path=root/'install.json'
if not config_path.exists():
    raise SystemExit('Musecraft is not installed. Follow setup.md and run install.sh first.')
config=json.loads(config_path.read_text())
if config.get('mode') != 'external-client' or config.get('systemdScope') not in ('user','system'):
    raise SystemExit('Invalid install.json; rerun install.sh using setup.md')
unit='musecraft-controller.service'
scope=['--user'] if config['systemdScope']=='user' else []
command=[str(root/'toolchain/node/bin/node'),str(Path(__file__).with_name('controller.cjs'))]
with (root/'start.lock').open('a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    active=subprocess.run(['systemctl',*scope,'is-active','--quiet',unit],check=False).returncode==0
    if not active:
        subprocess.run(['systemctl',*scope,'reset-failed',unit],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
        subprocess.run(['systemd-run',*scope,'--unit='+unit,'--property=Restart=on-failure','--property=RestartSec=5',
                        '--setenv=MUSECRAFT_HOME='+str(root),*command],check=True)
    deadline=time.monotonic()+10
    while True:
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                probe.settimeout(0.5)
                probe.connect(str(root/'controller.sock'))
            break
        except (OSError,TimeoutError):
            if time.monotonic()>=deadline:
                raise SystemExit('Controller did not become ready. Check musecraft-controller.service logs and stop any controller for another installation.')
            time.sleep(0.1)
    if '--quiet' not in sys.argv:
        print(json.dumps({'started':not active,'unit':unit,'note':'Controller ready; join a world before gameplay'}))
