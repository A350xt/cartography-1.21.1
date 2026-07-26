# Cartography

Cartography is a NeoForge 1.21.1 mod that serves a browsable web map of a running Minecraft server. It implements the Stage 1 (MVP) scope of `cartography_v2_technical_plan-1.pdf`: a top-down raster basemap, incremental dirty-chunk updates, a versioned tile namespace, and a bundled OpenLayers client.

## What it does

- **Top-down raster basemap** rendered with vanilla map colours and vanilla slope/water-depth shading, so the map reads the same as a map item.
- **Incremental updates.** Every server-side block change marks its chunk dirty; changes are debounced, merged, and grouped into metatile render jobs.
- **Zoom pyramid.** Max-zoom tiles are sampled from the world; lower zooms are produced by downsampling their children on a low-priority background pass.
- **TPS protection.** Only chunk copying happens on the server thread. Rendering pauses below a configured TPS and resumes above a higher one, so it cannot oscillate at the threshold.
- **Versioned tile URLs.** The tileset version hashes the world, dimension, profile, resource pack, material table, renderer, tile grid and format, so changing any of them yields a fresh namespace instead of stale cached tiles.
- **Conservative player markers.** Off by default; combat server modes never publish public positions.

## Build and test

```powershell
./gradlew test          # Java tests, plus the frontend build via resource processing
./gradlew build         # produces build/libs/cartography-1.0.0.jar
```

Frontend only:

```powershell
cd frontend
npm install
npx vitest run
npm run typecheck
npm run build
```

## Running it

Install the built jar into a NeoForge 1.21.1 server, start the server, and open <http://127.0.0.1:8080>.

The map binds to loopback by default. `ServerStartingEvent` also fires for singleplayer, so the mod forces loopback on an integrated server rather than exposing a private world to the LAN. To serve a dedicated server publicly, set `web.bindHost` explicitly and put it behind a reverse proxy.

Tiles are written under `<save>/cartography/`, so each world keeps its own cache.

## Configuration

Config lives in the generated NeoForge common config, grouped as in the technical plan's `cartography.yml`:

| Group | Notable keys |
| --- | --- |
| `web` | `enabled`, `bindHost`, `port`, `pendingTileRetryMs` |
| `renderer` | `maxZoom`, `pixelsPerBlockAtMaxZoom`, `tileOriginX/Z`, `metatileSize`, `paddingBlocks`, `configuredPackSignature` |
| `scheduler` | `pauseBelowTps`, `resumeAboveTps`, `dirtyDebounceSeconds`, `ancestorBudgetPerPass` |
| `markers` | `serverMode`, `mode`, `publicDelaySeconds`, `blurRadiusBlocks` |

Changing anything under `renderer` produces a new tileset version and therefore a fresh set of tiles.

## HTTP surface

| Endpoint | Purpose |
| --- | --- |
| `GET /manifest.json` | Tile grid contract: CRS, tile origin, pixels per block, signed extent, tileset version. `no-cache` with an ETag. |
| `GET /tiles/raster/{world}/{dimension}/{profile}/{tilesetVersion}/{z}/x{x}/y{y}.png` | A tile. Immutable cache when ready; a transparent placeholder with `X-Cartography-Tile-State: pending` and `Retry-After` while it renders. |
| `GET /markers?dimension=...` | Player markers that passed the privacy policy. |
| `GET /healthz` | Queue depth, ancestor backlog, TPS, cache hit ratio, snapshot/render/write timings. |

Tile coordinates are signed and carry explicit `x`/`y` prefixes, so negative regions cannot be confused with path traversal.

## Deviations from the technical plan

Two deliberate departures, both documented in the design spec:

- **PNG instead of WebP.** The plan specifies WebP at quality 85. Stock Java ships no WebP writer, and on flat-shaded palette tiles indexed PNG measured *smaller* than lossy WebP while staying lossless and free of native dependencies. Tiles are 8-bit indexed PNG and the URL extension matches what is actually encoded.
- **Surface classification does not follow map colour.** Vanilla's map walks down until a block has a map colour, but plain glass has none, so a glass roof is seen straight through. Cartography classifies by geometry and occlusion instead, which keeps glass buildings visible and generalizes to modded blocks.

## Not yet implemented

Stage 2 and beyond from the technical plan: the vector feature store, the web editor, permissions on features, POI ranking and label collision, MVT output, the plugin SDK, and PMTiles archives.

## Key paths

- Technical plan: `cartography_v2_technical_plan-1.pdf`
- Design: `docs/superpowers/specs/2026-06-27-cartography-neoforge-mvp-design.md`
- NeoForge API reference: `docs/neoforge-api-reference.md`
- Workflow: `docs/git-workflow.md`
- Implementation log: `docs/implementation-log.md`
