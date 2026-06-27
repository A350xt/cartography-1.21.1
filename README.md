# Cartography NeoForge MVP

Cartography is a NeoForge 1.21.1 mod that exposes a lightweight web map for a running Minecraft server. This MVP focuses on versioned raster tiles, lazy tile generation, a small embedded HTTP API, and a bundled OpenLayers frontend.

## MVP Features

- Embedded HTTP server for `manifest.json`, tiles, markers, health, and static frontend assets
- Versioned tile namespace under `tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp`
- Shared black pending tile with `Cache-Control: no-store`, `X-Cartography-Tile-State: pending`, and `Retry-After`
- Dirty chunk to metatile queueing plus ancestor invalidation bookkeeping
- Marker polling with `markerMode=off` as the default-safe behavior
- Standalone `frontend/` Vite project that is built into generated mod resources during Gradle runs
- Main-thread world snapshots with vanilla-style map palette and brightness shading for live tile rendering

## Build And Test

From the repository root:

```powershell
./gradlew test
```

This command runs Java tests and also triggers `npm install` plus `npm run build` for the frontend via Gradle resource processing.

For direct frontend iteration:

```powershell
cd frontend
npm install
npx vitest run --reporter verbose
npm run build
```

## Runtime Notes

- The embedded HTTP service reads its defaults from `Config.java` / the generated NeoForge common config.
- Marker publication is disabled by default. Enable it through the `markers.mode` config group if you want player markers.
- Tile output defaults to `run/cartography/tiles`.

## Key Paths

- Design: `docs/superpowers/specs/2026-06-27-cartography-neoforge-mvp-design.md`
- Plan: `docs/superpowers/plans/2026-06-27-cartography-neoforge-mvp.md`
- Root plan copy: `CARTOGRAPHY_MVP_PLAN.md`
- Workflow: `docs/git-workflow.md`
- Implementation log: `docs/implementation-log.md`
