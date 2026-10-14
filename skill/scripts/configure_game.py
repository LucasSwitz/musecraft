#!/usr/bin/env python3
"""Apply a reversible Minecraft rendering preset for small software-rendered VMs."""
import argparse
from pathlib import Path
import shutil

PRESET={'maxFps':'30','enableVsync':'false','renderDistance':'8',
        'simulationDistance':'6','graphicsMode':'0','particles':'2',
        'entityDistanceScaling':'0.75','fullscreen':'false',
        'overrideWidth':'854','overrideHeight':'480','gamma':'1.0','tutorialStep':'none'}

def configure(directory):
    options=directory/'options.txt'
    backup=directory/'options.before-musecraft-vm.txt'
    if options.exists() and not backup.exists():
        staged_backup=backup.with_suffix('.txt.pending')
        shutil.copyfile(options,staged_backup)
        staged_backup.chmod(0o600)
        staged_backup.replace(backup)
    lines=options.read_text().splitlines() if options.exists() else []
    # Preserve all unrelated settings verbatim, including colon-bearing values.
    kept=[line for line in lines if line.partition(':')[0] not in PRESET]
    staged=options.with_suffix('.txt.musecraft-pending')
    staged.write_text('\n'.join(kept+[key+':'+value for key,value in PRESET.items()])+'\n')
    staged.chmod(0o600)
    staged.replace(options)
    print('Applied 854x480 / 30 FPS / 8-chunk VM preset'+('; original options retained at '+str(backup) if backup.exists() else '; created new options'))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('minecraft_directory',type=Path)
    args=parser.parse_args()
    if not args.minecraft_directory.is_dir():parser.error('Minecraft directory does not exist')
    configure(args.minecraft_directory)
