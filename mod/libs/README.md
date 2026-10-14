# Baritone

Unmodified upstream `baritone-unoptimized-fabric-1.11.3.jar`, included as a separate
Fabric jar-in-jar dependency. Minecraft 1.21/1.21.1; LGPL-3.0.

- Binary: https://github.com/cabaletta/baritone/releases/download/v1.11.3/baritone-unoptimized-fabric-1.11.3.jar
- Corresponding source: https://github.com/cabaletta/baritone/tree/v1.11.3
- Source archive: https://github.com/cabaletta/baritone/archive/refs/tags/v1.11.3.tar.gz
- SHA-256: `02239bff27955bd842c2e132c2860689bc1190f2ca37b7f379213d8874d75903`

The build verifies this checksum. `BARITONE-LICENSE` contains the upstream
license. The library remains separately replaceable through Fabric's dependency
resolution; Musecraft uses its public builder/navigation APIs. The builder mixin separates inventory material availability from placement
orientation, matches simulated placements against the blueprint's explicit
properties, and constrains directional placement goals. Baritone still owns
path search, click simulation and construction. The unobfuscated upstream
artifact keeps these pinned integration points stable.
