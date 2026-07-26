# Cartography NeoForge MVP Design

**Status:** implemented. Aligned to `cartography_v2_technical_plan-1.pdf` (v2.0), Stage 1 scope.

## Goal

A NeoForge 1.21.1 mod that serves a browsable web map of a running server: top-down raster basemap, incremental dirty-chunk updates, a versioned tile namespace, a zoom pyramid, and conservative player markers.

Stage 2 and beyond — vector features, the editor, feature permissions, POI ranking, MVT, the plugin SDK, PMTiles — are explicitly out of scope.

## The plan's five hard invariants, and how each is met

The technical plan (§0) defines five invariants. They drive the design more than any feature list does.

| Invariant | Implementation |
| --- | --- |
| The basemap expresses appearance only | The renderer consumes `SampledMapBuffer` and knows nothing about roads, claims or POIs. Nothing semantic is baked into pixels. |
| Data coordinates are always raw Minecraft coordinates | `TileGrid` holds `tileOriginX/Z`, and it is the only place an origin is applied. Markers travel as raw block coordinates; the frontend transforms them at draw time. |
| Dirty updates are scheduled per metatile | `DirtyChunkTracker` debounces, `TileMath.dirtyChunkToTiles` maps to max-zoom tiles, `groupIntoMetatile` collapses them onto the metatile lattice. |
| Low zoom is maintained by an ancestor invalidation chain | `AncestorDirtySet` queues parents deepest-first; `TileDownsampler` rebuilds them from children on a budgeted idle pass. |
| Permission is a hard filter, not a score | `LocationPrivacyPolicy` drops disallowed positions from the candidate set entirely. There is no ranking stage that could leak them. |

## Architecture

| Package | Responsibility |
| --- | --- |
| `bootstrap` | Owns the tile pipeline: store, scheduler, dirty tracking, HTTP surface. Free of Minecraft types apart from the injected snapshot provider. |
| `config` | `web`, `renderer`, `scheduler`, `markers` groups, mirroring the plan's `cartography.yml`. |
| `core` | Tile grid, coordinate conversion, metatile grouping, ancestor and child chains, tileset version hashing. |
| `render` | Vanilla-accurate shading, downsampling, indexed-PNG encoding. |
| `scheduler` | Bounded workers with TPS hysteresis, debounce, ancestor queue. |
| `snapshot` | Server-thread chunk copying, off-thread sampling, surface classification. |
| `storage` | Atomic tile writes, signed-coordinate paths, tileset metadata. |
| `web` | Manifest, tiles, markers, health, static assets, privacy filter. |
| `mixin` | Block-change observation. |

Keeping `bootstrap` free of Minecraft types is deliberate: the entire tile lifecycle — miss, queue, render, store, downsample, serve — is testable without launching a server.

## Coordinate model (plan §4)

Three coordinate spaces, kept separate:

- **Data** — raw Minecraft block X/Z. What features and markers store. Never offset.
- **View** — max-zoom pixels, `(data - tileOrigin) * pixelsPerBlock`. Display only.
- **Tile** — signed online tiles, or non-negative published tiles with the offset recorded in the manifest.

`floorDiv` throughout, so block −1 lands in tile −1 rather than tile 0. Tile paths carry explicit `x`/`y` prefixes (`/8/x-3/y12.png`) so a negative coordinate cannot be read as a path separator or traversal.

The plan makes the round trip a mandatory test (§15.1). `CoordinateRoundTripTest` and `crs.test.ts` cover it on both sides, including a tile origin away from the world origin.

## Rendering (plan §5)

### Sampling is split across two threads

The server thread copies paletted containers and heightmaps (`ChunkColumnSnapshot`); all per-pixel work runs on the render worker. A padded 4×4 metatile is on the order of a million column scans, which must never happen inside a tick.

The copy is also a correctness requirement: `PalettedContainer.get` does not synchronize, so sampling a live chunk from a worker can miss an entry during a palette resize and crash the server.

### Surface classification (plan §5.3)

Vanilla's map walks down until a block has a map colour. Plain glass has none, so a glass roof is seen straight through and the floor below is drawn instead. Stained glass sets a colour and stops the walk — the asymmetry is the concrete bug.

`SurfaceClassifier` therefore classifies by geometry and occlusion, not colour:

fluid → empty shape (decoration) → thin overlay (`minY≈0`, `maxY≤0.25`) → `isSolidRender` (opaque) → leaves (foliage) → full-height and non-occluding (transparent structure) → fallback.

This generalizes to modded blocks, because `.noOcclusion()` plus an accurate shape is how transparency is authored. It also avoids the tag traps: `IMPERMEABLE` excludes ice and glass panes, `SNOW` includes the opaque snow block, `WOOL_CARPETS` excludes moss carpet.

Transparent structures and thin overlays are recorded as a translucent overlay colour and alpha-composited over the surface beneath, so a greenhouse reads as glass over floor.

### Shading matches vanilla

Ported from `MapItem#update`:

- Water: `depth * 0.1 + ((x+z)&1) * 0.2`; HIGH below 0.5, LOW above 0.9.
- Slope: `(h - hNorth) * 4 / (blocksPerPixel + 4) + (((x+z)&1) - 0.5) * 0.4`; HIGH above 0.6, LOW below −0.6.

Three details that are easy to get wrong and are pinned by tests:

- Depth shading is gated on the dominant colour being **water**, so lava uses slope shading, as in vanilla.
- Fluid depth is an `int` divided by the sample area, reproducing vanilla's truncation above one block per pixel.
- Row 0 is shaded against a **real sampled padding row**, matching vanilla's extra row at `l1 = -1`. Seeding from row 0 itself would flatten the first row of every metatile into a visible seam.

### Metatile padding (plan §6.3)

Metatiles are sampled expanded by `paddingBlocks`, rasterized, then cropped before child tiles are cut. Without it, the edge row of every metatile is shaded against nothing.

## Dirty pipeline (plan §6)

```
block change → dirty chunk → debounce/merge → max-zoom tiles
             → metatile jobs → render → cut tiles → mark ancestors → downsample
```

Block changes are observed by a mixin on `LevelChunk#setBlockState`. That is the one chokepoint every write funnels through — pistons, fluids, explosions, mob griefing, commands, bulk editors. The NeoForge block events cover only player break and place, and the update-notification hooks are gated behind block-update flags, which is what leaves holes in the map. Freshly generated terrain writes a proto chunk and bypasses the mixin, so it is picked up from `ChunkEvent.Load` with `isNewChunk()`.

The handler does nothing but a debounced set insert; `setBlockState` is one of the hottest methods in the game. No-op writes (null return) and same-block state churn are filtered out.

Low-zoom tiles are **downsampled from children, never re-sampled from the world**. A zoom-0 tile spans tens of thousands of blocks; sampling it directly would require loading far more chunks than a render job can justify. Ancestors pop deepest-first so children are current before their parent is built.

## Scheduling and TPS (plan §A.1)

Workers pause below `pauseBelowTps` and resume only above `resumeAboveTps`. The gap is deliberate — a single threshold makes workers flap on and off while TPS hovers at the limit.

TPS is `min(tickrate, 1e9 / (averageTickTimeNanos + 1))`. The cap matters: tick time measures only work, not the idle gap, so an idle server would otherwise report several hundred TPS.

Max-zoom jobs take priority; ancestor downsampling runs only when the queue is idle, since a stale low-zoom tile is far less visible than a missing max-zoom one.

## Caching (plan §7)

`tilesetVersion = sha256(world, dimension, profile, profileHash, resourcePack, materialTable, renderer, tileGrid, format, quality)`, truncated to 16 hex characters, and placed **in the tile path**. Recording it only in internal metadata would let a browser or CDN keep serving tiles rendered under an old resource pack.

- Ready tiles: `public, max-age=31536000, immutable`.
- Manifest: `no-cache` with an ETag on the tileset version.
- Pending tiles: `no-store`, plus `X-Cartography-Tile-State: pending` and `Retry-After`.

A request under a stale tileset version returns 404 rather than current pixels.

Writes are atomic — temp file, fsync, rename — so a reader sees either the old bytes or the complete new ones, never a partial tile.

### Pending tile contract

A miss returns a **transparent** placeholder with status 200, not a 404, so the client keeps a valid image in the grid and simply refreshes when the render lands. The frontend must key off the header, never off pixel content.

A miss below max zoom queues the max-zoom work that will feed it, skipping children that already exist — otherwise a client polling a low-zoom tile re-queues the same metatiles on every retry and starves the downsample pass that would satisfy it.

## Privacy (plan §9.3)

Markers are off by default. When enabled, `LocationPrivacyPolicy` applies as a hard filter before anything else sees a position:

| Server mode | Public map |
| --- | --- |
| building | exact, live |
| pve | exact after the publication delay |
| survival | quantized, after the delay |
| pvp, war | never published |

Spectators and invisible players are excluded. There is no vanilla vanish API, so the filter is composed from what exists.

## Deviations from the technical plan

Both are deliberate and evidence-backed.

**PNG instead of WebP.** The plan specifies WebP q85. Stock Java ships no WebP writer, and every Maven option bundles platform natives. Measured on tiles built from this project's palette, lossy WebP q85 came out **45% larger** than indexed PNG (17,834 vs 9,456 bytes) — a DCT codec is the wrong tool for flat-shaded palette art. Tiles are 8-bit indexed PNG, lossless and dependency-free, and `fileExtension()` returns `png` so the URL matches what is encoded.

**Classification instead of map colour.** Described under Rendering above.

## Observability (plan §15.2)

`/healthz` exposes `snapshotTimeMs`, `renderJobQueueDepth`, `metatileRenderMs`, `tileWriteLatencyMs`, `ancestorDirtyBacklog`, `cacheHitRatio`, plus TPS, scheduler state, and rendered/failed/dropped job counts.

## Frontend

Vite + OpenLayers, built into mod resources under `web/`.

The map is drawn in max-zoom pixel space using a custom `Projection` and `TileGrid` built from the manifest — Minecraft coordinates are not geographic, so no built-in projection applies. Minecraft Z grows south while OpenLayers Y grows north, so the Y axis is negated in `blockToPixel` and restored in `pixelToBlock`.

Markers arrive as raw block coordinates and are transformed at draw time, which is what keeps them aligned with the basemap. A cursor readout reports raw Minecraft coordinates, the only space a player can act on.

`tsc --noEmit` runs as part of `npm run build`; `vite build` alone does not type-check, and real type errors did slip through before this was wired in.

## Security defaults

- Markers off; combat modes never publish public positions.
- Binds to loopback, and forces loopback on an integrated server — `ServerStartingEvent` fires for singleplayer too, so an unguarded mod would expose a private world to the LAN.
- Static asset paths reject `..` and backslashes; tile paths reject traversal in every segment.
- Tiles are written inside the save directory, so each world keeps its own cache.
