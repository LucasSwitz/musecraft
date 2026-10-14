# Avatars

Source for the Figura avatars that ship in `skill/avatars/`. The skill's
installer copies them into the game; see `skill/references/player-model.md`.

## Jolly

The default Muse avatar. `jolly/shape.py` holds the measurements traced off the
original reference art (not included in this repository); `jolly/make_avatar.py` builds the rigged mesh, fur and
face texture, fitted armor shells and the avatar script (wave animation,
wearables, lighting fill). It uses only the Python standard library.

```bash
python3 avatar/jolly/make_avatar.py skill/avatars/Jolly
```

The build is deterministic apart from generated element UUIDs. `jolly/preview_mesh.py` renders the result lit like
Minecraft, for checking shape changes without the game (needs numpy and pillow;
pass a reference image as a third argument for a side-by-side, and set
`ARMOR_DIR` to Minecraft's `assets/minecraft/textures/models/armor` to preview
armor):

```bash
uv run --with numpy --with pillow python avatar/jolly/preview_mesh.py \
  skill/avatars/Jolly /tmp/jolly-preview.png
```

Then check it in a Figura client: copy the folder to
`<game dir>/figura/avatars/Jolly/`, run `/figura reload`, and look at walking,
sneaking, swimming, swinging, armor, head items and `musecraft animate name=wave`.
