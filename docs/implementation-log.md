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

## 2026-06-27 - Frontend OpenLayers client

**Purpose**

- Ship the MVP browser client that reads `manifest.json`, loads versioned tiles, and polls markers only when enabled

**Changes**

- Added a standalone `frontend/` Vite project with TypeScript, Vitest, and OpenLayers
- Implemented manifest loading, pending-tile retry logic, and marker polling helpers
- Added a browser app shell with dimension switching, status feedback, and map rendering
- Committed the npm lockfile and verified Gradle can build and sync the frontend bundle into generated mod resources

**Verification**

- `npx vitest run --reporter verbose`
- `npm run build`

**Next**

- Refresh the top-level README, run root verification, and record final evidence

## 2026-06-27 - Final verification and docs refresh

**Purpose**

- Replace the template README, verify the integrated build path from the repo root, and capture close-out evidence

**Changes**

- Rewrote `README.md` with cartography-specific build, test, and runtime guidance
- Verified the root Gradle pipeline now installs frontend dependencies, builds the frontend bundle, syncs assets, and runs Java tests in one pass
- Recorded the remaining workflow constraint that remote pushes still need explicit user approval in this environment
- Documented that the current renderer is a deterministic bootstrap path rather than full live world sampling

**Verification**

- `./gradlew test`

**Next**

- Request approval if the branch should be pushed to `origin/feat/neoforge-mvp-bootstrap`

## 2026-06-27 - Live world sampling and vanilla-style shading

**Purpose**

- Replace the bootstrap tile pattern with live world block sampling that tracks vanilla map shading more closely

**Changes**

- Added immutable sampled metatile buffers plus a pluggable world snapshot provider interface
- Implemented a server-thread snapshot provider that samples only already-loaded world columns, keeps missing chunks pending, and applies vanilla-style fluid handling
- Replaced the placeholder tile renderer with a vanilla-style palette and brightness renderer, then rasterized full metatiles before slicing leaf tiles so shading stays continuous across tile boundaries
- Wired the runtime bootstrap and test runtime entry points to consume snapshot providers instead of deterministic placeholder tiles
- Added focused renderer and HTTP contract tests that verify shading rules and pending-to-ready tile transitions

**Verification**

- `./gradlew test --tests "com.liedowncraft.cartography.render.VanillaMapTileRendererTest" --tests "com.liedowncraft.cartography.web.CartographyHttpContractTest"`
- `./gradlew test`

**Next**

- Validate the in-game visual result against a live world save and tune palette/shading edge cases if the map diverges from vanilla expectations
