# Implementation Log

Append one section per commit. Each section must record the intent, changed areas, verification evidence, and the next immediate step.

## 2026-06-27 - Documentation baseline

**Purpose**

- Align repository docs with `MVPPLAN.md`

**Changes**

- Added MVP design doc
- Added executable implementation plan and repo-root copy
- Added git workflow rules
- Added implementation log scaffold
- Expanded `.gitignore` for worktrees and frontend artifacts

**Verification**

- Pending: subsequent git diff review and file consistency checks

**Next**

- Replace NeoForge template defaults with cartography-specific build, config, backend, and frontend implementation

## 2026-06-27 - Backend runtime and build foundation

**Purpose**

- Replace the NeoForge template code with a cartography-specific runtime, tile math layer, and embedded HTTP contract

**Changes**

- Added grouped runtime settings and renderer profile defaults
- Replaced template mod bootstrap with an embedded Cartography runtime lifecycle
- Added tile coordinate math, metatile grouping, ancestor invalidation, and tileset version hashing
- Added a file-backed tile store, scheduler, deterministic raster renderer, and HTTP endpoints for manifest, tiles, markers, and health
- Wired JUnit into Gradle and added backend unit/integration tests
- Updated mod metadata and localization strings away from the template defaults

**Verification**

- `./gradlew test --tests "com.liedowncraft.cartography.core.TileMathTest" --tests "com.liedowncraft.cartography.core.TilesetVersionCalculatorTest" --tests "com.liedowncraft.cartography.ConfigDefaultsTest" --tests "com.liedowncraft.cartography.web.CartographyHttpContractTest"`

**Next**

- Add the Vite/OpenLayers frontend and connect it to the embedded manifest/tile APIs
