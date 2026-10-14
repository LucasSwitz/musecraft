# Muse player models

Muse players appear in game as custom characters instead of their Minecraft
skin. The default Muse avatar is **Jolly**: a round, cream-furred yeti plush
with a peach face window in a hood, stubby arms and short legs. The model mod is
**Figura**. Setup installs Figura and Jolly; uploading Jolly for an account is a
separate step that needs the account owner's approval.

## Figura

| | |
|---|---|
| Mod | Figura 0.1.6 for Minecraft 1.21.1, Fabric |
| File | `figura-0.1.6+1.21.1-fabric-mc.jar` |
| Source | `https://cdn.modrinth.com/data/s9gIPDom/versions/ugF3NunW/figura-0.1.6%2B1.21.1-fabric-mc.jar` |
| SHA-256 | `7b1278cadb52507abf6645955dcf3f92ec34bf9ea4a9a665c6a98423e2668628` |
| Needs | Java 21, Fabric Loader 0.15+; no Fabric API |
| Backend | `https://figura.moonlight-devs.org`, `wss://figura.moonlight-devs.org/ws` |

Figura draws avatars on the client. A viewer sees Jolly only when their own
client runs Figura and has fetched the wearer's avatar from Figura's backend;
vanilla clients see the account's Minecraft skin, which stays the fallback.
An avatar loaded locally but not uploaded is visible only to its wearer.

Uploading publishes the avatar under the account's name on Figura's public
backend, where every Figura client can download it. Get the account owner's
approval before uploading. Each account uploads its own copy.

## Setup

`install.sh` (directly or through `--setup-prism`) downloads the verified jar
into the game's `mods/` and copies the bundled `avatars/Jolly/` folder to
`<game dir>/figura/avatars/Jolly/`, where the game dir is the instance's
`minecraft/` directory. Rerunning setup refreshes both and leaves other avatars
alone. On a Muse VM, approve HTTPS to `cdn.modrinth.com` for the download.

To upload Jolly for the signed-in account, with the owner's approval and the
player in a world:

```text
musecraft avatar load /absolute/path/to/minecraft/figura/avatars/Jolly
musecraft avatar upload
musecraft avatar reload
musecraft avatar status
```

`load` selects the local avatar, as Figura's Wardrobe does. `upload` sends it
and makes it the account's avatar; it needs `connected` and `canUpload` from
`status` and fails with `figura_backend_not_ready` before Figura has signed in
to its backend. `reload` drops the local copy so Figura fetches avatars from its
backend again. Afterwards `status` reports `avatar.name: "Jolly"` with
`source: "backend"`, which confirms the upload. On a desktop the Wardrobe does
the same: open Figura from the pause screen, select Jolly and press Upload.

On a Muse VM the client needs HTTPS and websocket access to
`figura.moonlight-devs.org`. Approve the exact requests the game's Java process
makes, as described in [setup.md](../setup.md).

## Behavior

The meshes sit in groups named after vanilla parts (`Body`, `RightArm`,
`LeftArm`, `RightLeg`, `LeftLeg`), so every vanilla animation moves them:
walking, sprinting, sneaking, swimming, swinging and eating. He has no
neck: the hood stays with the body and the face slides around it toward where
the player looks (up to 70 degrees either way), without nodding. Held items sit
in the mitts.

`musecraft animations` lists Jolly's animations and `musecraft animate name=wave` plays
the wave: the right arm lifts beside the head and waggles for two seconds. A
Figura ping starts it on every viewer's client, so the account's avatar must be
uploaded and its client connected to Figura's backend.

Wearables follow what the player wears:

- Vanilla armor (leather in its dye color, chainmail, iron, gold, diamond,
  netherite and the turtle shell) appears as shells fitted to his body, using
  the material's vanilla texture. The helmet frames the face and turns with it.
- Carved pumpkins and mob heads sit over the hood; the face hides beneath them.
- Elytra fold along his back.
- Armor trims, enchantment glint and modded armor are not shown.

Armor textures use Minecraft 1.21.1's `textures/models/armor/` paths. Versions
from 1.21.2 moved them, so a game upgrade needs the script's paths updated.

## Limits

- Complexity: 1,662 faces bare and 2,042 in full armor. Other players' clients
  render at most 2,048 at Figura's default trust, so extra detail must come from
  elsewhere in the budget.
- Size: about 85 KB against the backend's 100 KiB upload limit.
- Shader packs recompute per-face lighting, so the smooth plush looks faceted
  under them.

## Making a custom model

A custom Muse model is a Figura avatar folder like Jolly's. Jolly is generated
from source in the Musecraft repository (`avatar/jolly/`); rebuild it there
rather than editing the folder. A custom model follows the same contract:

- Put meshes in the vanilla-named groups and item pivots
  (`RightItemPivot`, `LeftItemPivot`) in the hands.
- Give head items a `HelmetItemPivot` (blocks render 10 px wide, centered 4 px
  above it; mob heads stand on it) and elytra `LeftElytraPivot` and
  `RightElytraPivot` groups. Figura draws those vanilla renders at the pivots,
  scaled by the script.
- Hide vanilla armor (`vanilla_model.ARMOR`) and draw armor yourself on shapes
  that fit the body; vanilla armor boxes cannot fit a round body. Keep
  `vanilla_model.HELMET_ITEM` visible for head items.
- Offer animations to Musecraft with global `musecraft_animations()` (returning
  comma-separated names) and `musecraft_animate(name, value)` (returning true when
  it played). Start each animation through a ping so viewers see it.
- Stay inside the complexity and size limits above.

Test changes in a client with Figura before uploading: rebuild, run
`/figura reload`, and check walking, sneaking, swimming, swinging, armor, head
items and each animation from several angles.
