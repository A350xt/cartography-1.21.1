# Cartography NeoForge MVP Design

**Status:** approved execution baseline derived from `MVPPLAN.md`

## Goal

Build a NeoForge 1.21.1 MVP that exposes a browsable web map with lazy raster tile generation, versioned tile namespaces, player markers behind an opt-in switch, and a minimal embedded HTTP service. The scope is intentionally limited to Stage 1 PDF behavior and avoids editing tools, auth, and multi-platform abstractions.

## Scope

### Included

- NeoForge 1.21.1 only
- Embedded HTTP server for static frontend assets, tile API, manifest, markers, and health
- Top-down raster rendering at max zoom with metatile grouping
- Lazy tile generation with a shared black pending tile response
- Dirty chunk tracking plus ancestor invalidation bookkeeping
- Tileset version namespace derived from renderer inputs
- Frontend map client built with Vite and OpenLayers
- Marker polling with `markerMode=off` as the secure default

### Excluded

- Permission system
- Real-time streaming transports such as SSE or WebSocket
- Sidecar services
- Editing or annotation workflows
- Multi-loader abstractions

## Architecture

The mod is split into six subsystems with narrow responsibilities:

1. `bootstrap`
   Creates the runtime, binds NeoForge lifecycle hooks, and starts/stops the HTTP service.
2. `config`
   Owns `web`, `renderer`, `scheduler`, and `markers` config groups.
3. `core`
   Implements tile math, dirty chunk accumulation, metatile grouping, ancestor invalidation, and tileset version computation.
4. `render`
   Consumes immutable world-column snapshots captured on the server thread and produces vanilla-style raster tiles for max zoom.
5. `storage`
   Persists tiles in `/tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp` and serves cached bytes.
6. `web`
   Serves the frontend, manifest, markers, health, and tile endpoints.

The runtime performs world observation on the game side, captures immutable column snapshots on the server thread, queues image rendering off-thread, and writes tile files to disk. HTTP requests never render inline; they either return a cached tile or the shared pending tile and enqueue work.

## Pending Tile Contract

- A missing tile returns a pre-generated shared black tile byte array.
- Pending responses always include:
  - `Cache-Control: no-store`
  - `X-Cartography-Tile-State: pending`
  - `Retry-After`
- The frontend must treat the header as the source of truth. It must not infer pending state from pixel content.

## Scheduler And Dirty Propagation

- Dirty world updates are first normalized to chunk coordinates.
- Dirty chunks are mapped to max-zoom tiles.
- Max-zoom tiles are grouped into metatile jobs.
- Before a queued metatile is rasterized, the server thread captures a world snapshot for the exact covered block rectangle.
- After a metatile render completes, ancestor coordinates are marked stale so lower zooms can be refreshed lazily.
- The scheduler respects a TPS floor and pauses dequeuing when server performance is below the configured threshold.

## Live Sampling Model

- Background workers must not read `ServerLevel`, `LevelChunk`, `BlockState`, or other mutable world objects directly.
- Each metatile render starts by requesting a server-thread snapshot for the target dimension and block rectangle.
- The snapshot stores immutable per-column data:
  - sampled surface height
  - chosen visible block/fluid map color
  - fluid depth information when water-like columns are present
  - enough neighboring height continuity to reproduce vanilla-style brightness grading
- If a required chunk is not available for snapshot extraction, the job is allowed to stay pending rather than inventing colors from missing data.

## Vanilla-Style Shading

- Sampling and shading follow the structure of `MapItem.update(...)` rather than the old deterministic placeholder renderer.
- For non-ceiling dimensions:
  - find the top visible surface using `WORLD_SURFACE`
  - descend past `MapColor.NONE` blocks
  - treat exposed fluids similarly to vanilla by converting to the visible fluid block where appropriate
  - aggregate map colors over the sampling footprint
  - compute brightness from neighboring average heights
- For fluid-heavy columns:
  - derive brightness from fluid depth with the same qualitative branch structure as vanilla water shading
- The nether ceiling case remains supported with a deterministic fallback similar to vanilla’s ceiling behavior.

## HTTP Contract

### `GET /manifest.json`

Returns:

- `tileSize`
- `minZoom`
- `maxZoom`
- `pixelsPerBlockAtMaxZoom`
- `dimensions`
- `defaultDimension`
- `tilesetVersion`
- `tileUrlTemplate`
- `markerMode`
- `pendingTileRetryMs`

### `GET /tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp`

- Cache hit: return persisted tile bytes
- Cache miss: return the pending tile and enqueue the matching metatile

### `GET /markers?dimension={dimension}`

Returns:

- `players: [{ uuid, name, dimension, x, z, updatedAt }]`

When marker mode is `off`, the endpoint still returns a stable empty payload instead of leaking positions.

### `GET /healthz`

Returns:

- liveness
- queue depth
- paused/running scheduler state
- current tileset version

## Frontend

The frontend is a separate `frontend/` project built with Vite and OpenLayers. At startup it fetches `manifest.json`, then configures the map from server-provided values instead of hardcoded zooms or dimensions. Tile loading is implemented with a custom `tileLoadFunction` that fetches headers and image bytes manually. Marker polling is only enabled when `markerMode != "off"`.

## Build And Packaging

- Gradle keeps Aliyun mirrors first for general artifact resolution.
- Official NeoForged and Parchment repositories remain as authoritative fallbacks.
- Frontend assets are built by `npm` and copied into generated mod resources during Gradle resource processing.
- `.worktrees/` and frontend build output are ignored by git.

## Security And Defaults

- Marker mode defaults to `off`.
- The embedded service binds to a configurable host and port.
- Pending tiles use `no-store` so stale misses are not cached by browsers or proxies.
- Tileset versioning isolates renderer profile changes from previously generated tile trees.
