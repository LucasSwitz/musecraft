#!/usr/bin/env python3
"""Cheap source checks; release and live gameplay checks are separate."""
import ast
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
for directory in ('skill', 'scripts', 'tests'):
    for path in (root / directory).rglob('*'):
        if path.suffix == '.py':
            ast.parse(path.read_text(), filename=str(path))
        elif path.suffix in ('.cjs', '.js'):
            subprocess.run(['node', '--check', str(path)], check=True)
subprocess.run(['bash', '-n', str(root / 'skill/install.sh')], check=True)
print('Python, JavaScript and installer syntax checks passed')
