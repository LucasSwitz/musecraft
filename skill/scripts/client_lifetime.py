#!/usr/bin/env python3
"""Keep a prepared, licensed client inside the player's managed exec.

With --login, run only Prism's window (no game) for the one-time Microsoft
sign-in, and exit when Prism closes."""
import argparse
import fcntl
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import time


def listening(path):
    try:
        with socket.socket(socket.AF_UNIX) as probe:
            probe.settimeout(0.5)
            probe.connect(str(path))
        return True
    except OSError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--display', required=True)
    parser.add_argument('--headless', action='store_true')
    parser.add_argument('--login', action='store_true')
    args = parser.parse_args()
    root = Path(os.environ['MUSECRAFT_HOME'])
    launcher = root / 'bin/launch-minecraft'
    if not launcher.is_file():
        raise SystemExit('client_not_installed: run install.sh --setup-prism first')
    if not re.fullmatch(r':\d+(?:\.\d+)?', args.display):
        raise SystemExit('invalid_display: use a local X display such as :95')
    os.environ['DISPLAY'] = args.display
    stopped = False

    def stop(_signal, _frame):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    display = None
    game = None
    with (root / 'client-lifetime.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit('client_already_supervised: another managed process owns this installation')
        # Lets the controller tell a starting client from a missing one, and
        # `musecraft login stop` find the sign-in window.
        pid_file = root / ('client-login.pid' if args.login else 'client-lifetime.pid')
        pid_file.write_text(str(os.getpid()))
        try:
            # Adopt a live client without restarting it. If it later disappears,
            # the same managed execution can launch the prepared Prism instance.
            while not stopped:
                if game is not None and game.poll() is not None:
                    if args.login:
                        print('Prism closed.', flush=True)
                        return
                    raise SystemExit('client_launcher_exited: inspect Prism on the desktop and its login state')
                if (args.login or not listening(root / 'client.sock')) and game is None:
                    x_socket = Path('/tmp/.X11-unix/X' + args.display[1:].split('.')[0])
                    if not listening(x_socket):
                        if not args.headless:
                            raise SystemExit('display_unavailable: restore the desktop or enable headless setup')
                        display = subprocess.Popen(['Xvfb', args.display, '-screen', '0', '1600x900x24', '-nolisten', 'tcp'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                        while not stopped and not listening(x_socket):
                            if display.poll() is not None:
                                raise SystemExit('display_start_failed: Xvfb exited')
                            time.sleep(0.1)
                    if not stopped:
                        # Launcher output may contain account/process metadata.
                        # Keep it out of the player's transcript.
                        env = {**os.environ, 'MUSECRAFT_PRISM_UI': '1'} if args.login else None
                        game = subprocess.Popen([str(launcher)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
                time.sleep(0.5)
        finally:
            pid_file.unlink(missing_ok=True)
            for process in [game, display]:
                if process is not None and process.poll() is None:
                    process.terminate()


if __name__ == '__main__':
    main()
