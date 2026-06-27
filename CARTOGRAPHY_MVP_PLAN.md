# Cartography NeoForge MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the NeoForge 1.21.1 cartography MVP with embedded HTTP APIs, lazy tile generation, versioned raster tiles, a Vite/OpenLayers frontend, and the required project documentation/workflow.

**Architecture:** Use a small runtime bootstrap that owns config, tile math, a file-backed tile store, a bounded render scheduler, an embedded HTTP server, and a static frontend bundle. Keep world-facing behavior thin and move most logic into pure Java services so dirty propagation, versioning, and HTTP behavior are testable without launching Minecraft.

**Tech Stack:** Java 21, NeoForge 1.21.1, Gradle, JUnit 5, Vite, TypeScript, OpenLayers, Vitest

---

### Task 1: Repository And Documentation Baseline

**Files:**
- Modify: `.gitignore`
- Create: `docs/superpowers/specs/2026-06-27-cartography-neoforge-mvp-design.md`
- Create: `docs/superpowers/plans/2026-06-27-cartography-neoforge-mvp.md`
- Create: `CARTOGRAPHY_MVP_PLAN.md`
- Create: `docs/git-workflow.md`
- Create: `docs/implementation-log.md`

- [ ] Step 1: Update ignore rules for worktrees and frontend artifacts.
- [ ] Step 2: Write the MVP design doc aligned to `MVPPLAN.md`.
- [ ] Step 3: Write this implementation plan and copy it to the repo root.
- [ ] Step 4: Add `docs/git-workflow.md` with branch, commit, document-sync, and push rules.
- [ ] Step 5: Add `docs/implementation-log.md` with an append-only structure for per-commit status.
- [ ] Step 6: Commit and push the documentation baseline with a conventional commit message.

### Task 2: Build, Metadata, And Config Foundations

**Files:**
- Modify: `settings.gradle`
- Modify: `build.gradle`
- Modify: `gradle.properties`
- Modify: `src/main/templates/META-INF/neoforge.mods.toml`
- Replace: `src/main/java/com/liedowncraft/cartography/Config.java`
- Modify: `docs/implementation-log.md`

- [ ] Step 1: Add or preserve Aliyun-first public repositories while keeping official NeoForged/Parchment fallbacks.
- [ ] Step 2: Introduce frontend build tasks and generated resource wiring in Gradle.
- [ ] Step 3: Replace template config with `web`, `renderer`, `scheduler`, and `markers` groups.
- [ ] Step 4: Update mod metadata and descriptive text away from the NeoForge template defaults.
- [ ] Step 5: Run a non-destructive Gradle dependency-resolution check.
- [ ] Step 6: Commit and push the foundation changes together with implementation-log updates.

### Task 3: Backend Runtime And Tile Services

**Files:**
- Replace: `src/main/java/com/liedowncraft/cartography/Cartography.java`
- Replace: `src/main/java/com/liedowncraft/cartography/CartographyClient.java`
- Create: `src/main/java/com/liedowncraft/cartography/bootstrap/**`
- Create: `src/main/java/com/liedowncraft/cartography/config/**`
- Create: `src/main/java/com/liedowncraft/cartography/core/**`
- Create: `src/main/java/com/liedowncraft/cartography/render/**`
- Create: `src/main/java/com/liedowncraft/cartography/storage/**`
- Create: `src/main/java/com/liedowncraft/cartography/web/**`
- Modify: `docs/implementation-log.md`

- [ ] Step 1: Write failing unit tests for tile math, metatile grouping, ancestor invalidation, and tileset version generation.
- [ ] Step 2: Implement pure model and utility classes until the tests pass.
- [ ] Step 3: Write failing tests for marker default-off behavior, pending-tile headers, and scheduler pause semantics.
- [ ] Step 4: Implement the runtime, tile store, scheduler, manifest endpoint, markers endpoint, health endpoint, and pending tile behavior.
- [ ] Step 5: Add the embedded HTTP bootstrap and mod lifecycle wiring.
- [ ] Step 6: Commit and push the backend MVP slice together with implementation-log updates.

### Task 4: Frontend Map Client

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/vitest.config.ts`
- Create: `frontend/src/**`
- Modify: `build.gradle`
- Modify: `docs/implementation-log.md`

- [ ] Step 1: Create the Vite/OpenLayers project structure and test harness.
- [ ] Step 2: Write failing frontend tests for manifest bootstrapping, tile retry behavior, and marker polling enablement.
- [ ] Step 3: Implement the map bootstrap, dimension switching, custom tile loader, and marker polling.
- [ ] Step 4: Wire the frontend build into Gradle resource generation.
- [ ] Step 5: Build the frontend bundle and verify the generated assets are available to the mod resources pipeline.
- [ ] Step 6: Commit and push the frontend MVP slice together with implementation-log updates.

### Task 5: Integration Tests And Final Verification

**Files:**
- Create: `src/test/java/com/liedowncraft/cartography/**`
- Create: `frontend/src/**/*.test.ts`
- Modify: `docs/implementation-log.md`
- Modify: `README.md`

- [ ] Step 1: Add backend integration tests for HTTP startup, manifest contract, tile miss queueing, dirty invalidation, scheduler pause, and repository mirror configuration.
- [ ] Step 2: Add frontend tests for manifest error handling, tile URL generation, pending retries, and marker polling behavior.
- [ ] Step 3: Run `./gradlew test` and `npm --prefix frontend test -- --run`.
- [ ] Step 4: Update `README.md` with local build/run instructions for the embedded map.
- [ ] Step 5: Append the final verification evidence to `docs/implementation-log.md`.
- [ ] Step 6: Commit and push the verification/documentation slice together with implementation-log updates.
