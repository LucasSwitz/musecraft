#!/usr/bin/env python3
"""See and drive the client's X display without extra packages: screenshots
through ffmpeg's x11grab, and clicks and keys through the X server's XTEST
extension, spoken directly over the display's Unix socket.

Usage: screen.py [--display :95] shot | click X Y [--button N] [--double] |
                 move X Y | type TEXT | key NAME[+NAME...]"""
import argparse
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import time

KEYSYMS = {'return': 0xff0d, 'enter': 0xff0d, 'tab': 0xff09, 'escape': 0xff1b, 'esc': 0xff1b,
           'backspace': 0xff08, 'delete': 0xffff, 'home': 0xff50, 'end': 0xff57, 'left': 0xff51,
           'up': 0xff52, 'right': 0xff53, 'down': 0xff54, 'pageup': 0xff55, 'pagedown': 0xff56,
           'space': 0x20, 'shift': 0xffe1, 'ctrl': 0xffe3, 'control': 0xffe3, 'alt': 0xffe9,
           'super': 0xffeb, **{f'f{n}': 0xffbd + n for n in range(1, 13)}}
SHIFT = 0xffe1


class Display:
    """The few X11 requests needed: setup, XTEST input, keyboard map, sync."""

    def __init__(self, name):
        number = re.fullmatch(r':(\d+)(?:\.\d+)?', name)
        if not number:
            raise SystemExit('invalid_display: use a local X display such as :95')
        self.sock = socket.socket(socket.AF_UNIX)
        try:
            self.sock.connect(f'/tmp/.X11-unix/X{number[1]}')
        except OSError:
            raise SystemExit(f'display_unavailable: nothing is running on {name}; start `musecraft login` or `musecraft client` first')
        self.sock.sendall(struct.pack('<BxHHHHxx', 0x6c, 11, 0, 0, 0))
        status, _, _, _, length = struct.unpack('<BBHHH', self.read(8))
        body = self.read(length * 4)
        if status != 1:
            raise SystemExit('display_refused: ' + body.decode('latin-1', 'replace').strip('\0 '))
        vendor_length = struct.unpack_from('<H', body, 16)[0]
        formats = body[21]
        self.min_keycode, self.max_keycode = body[26], body[27]
        offset = 32 + (vendor_length + 3) // 4 * 4 + 8 * formats
        self.root = struct.unpack_from('<I', body, offset)[0]
        self.width, self.height = struct.unpack_from('<HH', body, offset + 20)
        self.xtest = self.query_extension(b'XTEST')
        self.keymap = None

    def read(self, size):
        data = b''
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise SystemExit('display_closed')
            data += chunk
        return data

    def reply(self):
        while True:
            head = self.read(32)
            if head[0] == 0:
                raise SystemExit(f'x11_error_{head[1]}')
            if head[0] == 1:
                return head + self.read(struct.unpack_from('<I', head, 4)[0] * 4)

    def query_extension(self, name):
        pad = (4 - len(name) % 4) % 4
        self.sock.sendall(struct.pack('<BxHHxx', 98, (8 + len(name) + pad) // 4, len(name)) + name + b'\0' * pad)
        head = self.reply()
        if not head[8]:
            raise SystemExit('xtest_unavailable: the display does not offer XTEST')
        return head[9]

    def fake(self, kind, detail, x=0, y=0):
        self.sock.sendall(struct.pack('<BBHBBxxII8xhh4x2xxB', self.xtest, 2, 9, kind, detail, 0,
                                      self.root if kind == 6 else 0, x, y, 0))

    def sync(self):
        self.sock.sendall(struct.pack('<BxH', 43, 1))  # GetInputFocus round trip
        self.reply()

    def keycode(self, keysym):
        if self.keymap is None:
            count = self.max_keycode - self.min_keycode + 1
            self.sock.sendall(struct.pack('<BxHBBxx', 101, 2, self.min_keycode, count))
            data = self.reply()
            per = data[1]
            syms = struct.unpack_from(f'<{count * per}I', data, 32)
            self.keymap = {}
            for index in range(count):
                for column in range(min(per, 2)):
                    sym = syms[index * per + column]
                    if sym and sym not in self.keymap:
                        self.keymap[sym] = (self.min_keycode + index, column == 1)
        if keysym not in self.keymap:
            raise SystemExit(f'unknown_key: keysym {keysym:#x} has no key on this display')
        return self.keymap[keysym]


def keysym(part):
    if part.lower() in KEYSYMS:
        return KEYSYMS[part.lower()]
    if len(part) == 1:
        return ord(part)
    raise SystemExit(f'unknown_key: {part}; use a character or one of ' + ', '.join(sorted(KEYSYMS)))


def press(display, keysyms):
    codes = []
    for keysym in keysyms:
        code, shifted = display.keycode(keysym)
        if shifted:
            codes.append(display.keycode(SHIFT)[0])
        codes.append(code)
    for code in codes:
        display.fake(2, code)
    for code in reversed(codes):
        display.fake(3, code)
    display.sync()
    time.sleep(0.03)


def shot(name, display):
    folder = Path(os.environ.get('MUSECRAFT_HOME', '/tmp')) / 'screens'
    folder.mkdir(parents=True, exist_ok=True)
    out = folder / time.strftime('screen-%Y%m%d-%H%M%S.png')
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'x11grab', '-video_size', f'{display.width}x{display.height}',
                    '-i', name, '-frames:v', '1', str(out)], check=True)
    print(f'{out} ({display.width}x{display.height}; read the PNG to see it)')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--display', default=':95')
    parser.add_argument('command', choices=['shot', 'click', 'move', 'type', 'key'])
    parser.add_argument('args', nargs='*')
    parser.add_argument('--button', type=int, default=1)
    parser.add_argument('--double', action='store_true')
    args = parser.parse_args()
    display = Display(args.display)
    if args.command == 'shot':
        return shot(args.display, display)
    if args.command in ('click', 'move'):
        x, y = (int(float(v)) for v in args.args[:2])
        display.fake(6, 0, x, y)
        display.sync()
        if args.command == 'click':
            for _ in range(2 if args.double else 1):
                time.sleep(0.05)
                display.fake(4, args.button)
                display.fake(5, args.button)
                display.sync()
        return print(f'{args.command} {x},{y}')
    if args.command == 'type':
        text = ' '.join(args.args)
        for char in text:
            press(display, [0xff0d if char == '\n' else ord(char)])
        return print(f'typed {len(text)} characters')
    for combo in args.args:
        press(display, [keysym(part) for part in combo.split('+')])
    print('pressed ' + ' '.join(args.args))


if __name__ == '__main__':
    main()
