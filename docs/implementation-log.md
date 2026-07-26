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

## 2026-07-26 - Align the implementation with the v2.0 technical plan

**Purpose**

- Bring the MVP in line with `cartography_v2_technical_plan-1.pdf`, whose hard invariants the earlier slices did not yet satisfy

**Changes**

- Added the canonical coordinate model: `TileGrid` (tile origin, pixels per block, signed tiles), `TileGridNormalization` for published tilesets, and the round-trip tests the plan lists as mandatory
- Reworked `tilesetVersion` to hash world, dimension, profile, resource pack, material table, renderer, tile grid, format and quality, and moved it into the tile path as `/tiles/raster/{world}/{dimension}/{profile}/{tilesetVersion}/{z}/x{x}/y{y}.png`
- Replaced delete-only ancestor invalidation with a real downsample pass: `AncestorDirtySet` pops deepest-first and `TileDownsampler` rebuilds parents from children on a budgeted idle pass, so low zoom can now render at all
- Added metatile padding and crop so the first row of every metatile is shaded against real terrain instead of producing a seam
- Made tile writes atomic (temp, fsync, rename) and added tileset `metadata.json`
- Replaced map-colour-driven surface detection with `SurfaceClassifier`, which classifies by geometry and occlusion; this is what stops glass roofs being seen through, and it generalizes to modded blocks
- Split world sampling: the server thread only copies paletted containers (`ChunkColumnSnapshot`), and all per-pixel work moved to the render worker. Reading live chunks off-thread could crash the server on a palette resize
- Wired the previously dead pipeline: a `LevelChunk#setBlockState` mixin observes every block change, `ChunkEvent.Load` catches new terrain, `ServerTickEvent.Post` drives TPS and drains the debounce, and markers publish through `LocationPrivacyPolicy`
- Added TPS hysteresis (pause below, resume above) so workers cannot flap at the threshold
- Switched tiles to indexed PNG: stock Java has no WebP writer, and measurement put lossy WebP q85 45% larger than indexed PNG on this content
- Rebuilt the frontend on a manifest-derived OpenLayers `TileGrid` and `Projection`, so markers in block coordinates land on the correct pixels
- Added the observability metrics from plan section 15.2 to `/healthz`

**Defects found and fixed while verifying**

Packaging and build:

- The frontend bundle was being packaged at `assets/` instead of `web/`, and `index.html` was missing from the jar entirely, so no bundled asset was reachable at runtime. The resource source directory pointed at the bundle folder itself, which stripped the `web/` prefix. Added `StaticAssetContractTest` to pin the serving contract
- `vite build` does not type-check, and two real type errors were hiding in `app.ts`. `npm run build` now runs `tsc --noEmit` first
- `loadTileIntoImage` bound `URL.revokeObjectURL` eagerly, so a failed tile request threw a binding error instead of surfacing its own

Security:

- **Path traversal via the tile extension.** `TilePath.parse` validated the extension *before* URL-decoding it, so `y0.%2e%2e%2fpng` decoded to `../png` and escaped the tile store root. Replaced pattern blocklisting with decode-then-allowlist validation on every segment. `TilePathTest` now covers encoded traversal
- The static asset handler had the same decode-order flaw. It now decodes first and allowlists each path segment
- **Unauthenticated denial of service.** A miss on a low-zoom tile recursed over the entire pyramid beneath it: one request for a zoom-0 tile performed roughly 87,000 filesystem stats on an HTTP thread. Replaced with a breadth-first expansion capped at 256 tiles; `MissExpansionBoundTest` pins the bound

Correctness:

- **`blocksPerPixel` was zero at max zoom.** The default profile renders 2 pixels per block, so a 256px tile spans 128 blocks and integer division gave `128 / 256 == 0`. Every max-zoom sample footprint would have collapsed. Block extents are now derived from the pixel lattice (`firstBlockOfPixel` / `blockSpanOfPixel`), which is exact whether a pixel covers many blocks or several pixels share one. `TileGridPixelMappingTest` pins the lattice at every zoom
- The renderer keyed water depth shading on `SurfaceKind.FLUID_SURFACE`, which would have shaded lava as deep water. Vanilla gates on the dominant colour being water; corrected to match
- The scheduler defaulted to running regardless of the configured TPS floor, so a scheduler configured to pause below an unreachable threshold started unpaused

**Verification**

- `./gradlew test` — 94 backend tests, 0 failures
- `npx vitest run` — 23 frontend tests, 0 failures
- `npm run typecheck` — clean
- `./gradlew clean build` — jar contains `web/index.html`, `web/assets/`, and the mixin class

**Live server verification**

Ran a dedicated NeoForge 1.21.1 server against a generated world (EULA accepted on the user's
explicit instruction) and drove it over HTTP and RCON.

Confirmed working at runtime:

- **Mixin applies.** The log shows `cartography.mixins.json:LevelChunkSetBlockStateMixin` injecting into
  `LevelChunk#setBlockState`, with no mixin errors
- **Dirty pipeline fires.** 123 block changes from `/setblock` and `/fill` collapsed into 4 dirty
  chunks, which drained into render jobs and then to zero
- **Tiles render from real world data.** A max-zoom tile came back as a 256x256 indexed PNG with a
  34-entry palette, 13,253 bytes, `Cache-Control: public, max-age=31536000, immutable` and no pending
  header. Timings: snapshot 107ms, render 27ms, write 39ms
- **Shading matches vanilla exactly.** Every colour in the decoded tile is a vanilla map palette entry
  at a vanilla brightness: PLANT at LOW/NORMAL/HIGH (slope shading) and WATER at LOW/NORMAL/HIGH
  (depth shading), plus SAND at NORMAL
- **Zoom pyramid builds by downsampling.** After the dirty re-render, 16 ancestors refreshed and tiles
  exist at every zoom from 8 down to 0, decreasing in size as detail is lost (13,313 / 1,035 / 174
  bytes at zoom 8 / 4 / 0)
- **Pending contract, tileset versioning, world id persistence and tileset metadata** all behaved as
  designed; changing `metatileSize` in the config produced a fresh tileset version

Defect found only by the live run:

- **Sub-block sampling requested twice the chunks it needed.** `blocksBetweenPixels` clamped to at
  least one block per pixel, but at 2 pixels per block 260 pixels span 130 blocks, not 260. Every job
  demanded a region twice as wide as required, and the surplus chunks were usually not loaded, so
  every render failed with `SnapshotUnavailableException`. The health endpoint's `lastFailure` field,
  added while debugging this, is what made it diagnosable

**Still not verified**

- Performance of the block-change hook under sustained bulk edits is not profiled. The smoke test
  used a 121-block fill, which is far below a WorldEdit-scale operation
- Only the overworld was exercised. The nether ceiling fallback and the end are untested at runtime
- Transparent-structure compositing (the glass-roof case) is covered by unit tests but was not
  reproduced in a live world
- The adversarial self-review workflow failed with API errors before any reviewer returned, so those
  six review dimensions remain uncovered

**Next**

- Profile the block-change hook under a large WorldEdit-style operation
- Exercise the nether and end at runtime
- Re-run the adversarial review when the API is available
