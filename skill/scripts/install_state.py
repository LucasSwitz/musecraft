#!/usr/bin/env python3
"""Refuse live game/controller sockets; remove only demonstrably stale ones."""
import errno
from pathlib import Path
import socket
import stat
import sys


def ensure_stopped(root):
    for name in ('controller.sock', 'client.sock'):
        path = root / name
        try:
            before = path.lstat()
        except FileNotFoundError:
            continue
        if not stat.S_ISSOCK(before.st_mode):
            raise RuntimeError(f'{path} is not a socket; choose a clean installation directory')
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                probe.settimeout(1)
                probe.connect(str(path))
        except OSError as error:
            if error.errno not in (errno.ECONNREFUSED, errno.ENOENT):
                raise RuntimeError(f'Cannot establish whether {name} is stopped; close the game/controller and retry') from error
            try:
                after = path.lstat()
            except FileNotFoundError:
                continue
            if (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino):
                raise RuntimeError(f'{name} changed during setup; close the game/controller and retry')
            path.unlink(missing_ok=True)
        else:
            raise RuntimeError('Close Minecraft and stop the Musecraft controller before installing')


if __name__ == '__main__':
    try:
        ensure_stopped(Path(sys.argv[1]))
    except (OSError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
