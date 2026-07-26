# NeoForge 1.21.1 API reference for Cartography

Verified against the decompiled, NeoForge-patched sources for Minecraft 1.21.1 / NeoForge 21.1.234 in the local Gradle cache. Names are Mojang official mappings, which is what ModDevGradle compiles against.

Anything not confirmed at runtime is listed under [Unverified](#unverified--needs-runtime-check).

## Decisions

| Area | Approach |
| --- | --- |
| Block changes | Mixin `LevelChunk#setBlockState` at `RETURN`; it is the one chokepoint all writes funnel through. |
| New terrain | `ChunkEvent.Load` with `isNewChunk() == true`; worldgen writes a proto chunk and bypasses the mixin. |
| TPS | `min(tickRateManager().tickrate(), 1e9 / (getAverageTickTimeNanos() + 1))`. |
| Tick hook | `ServerTickEvent.Post` on `NeoForge.EVENT_BUS`. |
| Chunk reading | Copy paletted containers on the server thread; sample the copy on a worker. |
| Tile format | 8-bit indexed PNG. No WebP writer exists in stock Java. |
| World id | Persist a generated UUID inside the save directory. |

## Block change detection

`LevelChunk#setBlockState` (`LevelChunk.java:239`):

```java
@Nullable @Override
public BlockState setBlockState(BlockPos pos, BlockState state, boolean isMoving)
```

Returns the **previous** state, or **`null`** when nothing changed. The null check is load-bearing: without it, no-op writes mark chunks dirty and thrash the render queue.

Everything reaches it via `Level#setBlock` → `LevelChunk#setBlockState` (`Level.java:253`): pistons (`PistonBaseBlock` 188/203/217/305/346/362/369), fluid flow (`FlowingFluid` 261/455/459), explosions (`IBlockExtension#onBlockExploded:740`), `destroyBlock` (`Level.java:332`), and all command and bulk-editor writes. No direct bypass exists anywhere in `net/minecraft`.

Rejected alternatives, all of which leave holes in the map:

| Target | Why not |
| --- | --- |
| `BlockEvent.BreakEvent` | Constructed at one call site reachable only from `ServerPlayerGameMode.java:252`. Player breaks only. |
| `BlockEvent.EntityPlaceEvent` | Entity-driven placement only. |
| `Level#setBlock` | Skips `markAndNotifyBlock` entirely when `captureBlockSnapshots` is true. |
| `ServerLevel#sendBlockUpdated` / `ServerChunkCache#blockChanged` | Gated behind block-update flag 2 plus `BLOCK_TICKING`. Misses worldgen and non-notifying bulk sets. |
| `ServerLevel#blockUpdated` | Gated behind flag 1. It is a neighbour-update hook, not a change hook. |
| `ExplosionEvent.Detonate` | Fires *before* removal with a mutable list. A prediction, not an observation. |

`LevelChunk#setBlockState` is extremely hot (every fluid tick, piston move, explosion block), so the handler must be O(1) — a debounced set insert and nothing else.

### Mixin infrastructure

Already wired: `cartography.mixins.json` is declared in `neoforge.mods.toml`, and `sponge-mixin 0.15.2` plus `mixinextras-neoforge 0.5.3` are on the compile classpath via ModDevGradle. No `build.gradle` change and no refmap needed — 1.21.1 uses Mojang official names.

### New chunks

`ChunkEvent.Load` (`ChunkEvent.java:55`) fires for both generated and disk-loaded chunks; `isNewChunk()` distinguishes them and is only ever true on the logical server.

Its own javadoc warns the event may fire **before** the chunk reaches `ChunkStatus.FULL`, and that touching the level in the listener causes chunk-loading deadlocks. Record the `ChunkPos` only. `getLevel()` returns `LevelAccessor`, so guard with `instanceof ServerLevel`.

## Tick timing and TPS

On `MinecraftServer`:

```java
public long  getAverageTickTimeNanos()      // nanoseconds, rolling mean over <=100 ticks
public long[] getTickTimesNanos()           // nanoseconds, live internal array — copy before use
public float getCurrentSmoothedTickTime()   // MILLISECONDS, 0.8/0.2 EMA
public ServerTickRateManager tickRateManager()
public int   getTickCount()
```

`getAverageTickTime()` (the old float-ms method) **does not exist** in 1.21.1. `TickRateManager`'s accessor is `tickrate()` — no `get` prefix.

The cap is required. Tick time measures only work, not the idle gap between ticks, so an idle server reports several hundred uncapped "TPS". Mojang's own `computeNextAutosaveInterval()` (`MinecraftServer.java:977`) does the same thing, including the `+ 1L` divide-by-zero guard.

## Server tick event

`net.neoforged.neoforge.event.tick.ServerTickEvent`, nested `.Pre` and `.Post`. Not cancellable; the old `TickEvent.Phase` split is now the two classes. The legacy `net.minecraftforge.*` API is entirely absent from this toolchain.

Goes on **`NeoForge.EVENT_BUS`** (the game bus). The mod bus actively throws on non-`IModBusEvent` types.

`Post` fires after the tick-time tally block, so measure there. `hasTime()` is a live `BooleanSupplier` backed by `MinecraftServer#haveTime()` — the cheapest correct "do I have spare time this tick" gate.

With `@EventBusSubscriber`, omit `bus=`; it is deprecated-for-removal and ignored in 1.21.1.

## Main-thread submission

`MinecraftServer extends ReentrantBlockableEventLoop<TickTask>`:

```java
public <V> CompletableFuture<V> submit(Supplier<V> supplier)
public void execute(Runnable task)
public void executeIfPossible(Runnable task)   // throws RejectedExecutionException once stopped
public boolean isSameThread()
```

Two hazards:

1. **Once the server is stopped, `submit` runs the supplier inline on the calling thread** rather than scheduling it (`scheduleExecutables()` returns false). World-touching code would then execute off the server thread with no error raised. Guard with `isStopped() || !isRunning()` before submitting.
2. **Queued tasks are never drained on shutdown.** An unbounded `.join()` can block a worker forever. Use `.get(timeout, MILLISECONDS)`.

Called from the server thread, `submit(...).join()` is safe: the supplier runs inline and the future is already complete, so there is no deadlock.

Submitted tasks are force-run once 3+ ticks stale (`shouldRun`, `MinecraftServer.java:852`), bounding normal latency at roughly 4 ticks.

## Chunk reading

**Reading a live chunk off-thread can crash the server.** `PalettedContainer.get` does not `acquire()`, so a concurrent palette resize yields `MissingPaletteEntryException` → `ReportedException`, with no threading-detector warning first.

The safe pattern, and what squaremap and Dynmap do: copy on the server thread, sample the copy.

```java
LevelChunkSection#getStates()      // PalettedContainer<BlockState>
PalettedContainer#copy()           // deep copy, safe to hand to a worker
ChunkAccess#getSections()
ChunkAccess#getHeight(Heightmap.Types, int, int)
```

`ServerChunkCache#getChunk` called off-thread bounces to the main thread and **blocks the caller**, and `managedBlock` spins the main thread. `getChunkNow` returns null off-thread. So all acquisition must happen on the server thread.

Use `getChunk(x, z, ChunkStatus.FULL, false)` — `requireChunk=true` would add a ticket and run full worldgen. Unwrap `ImposterProtoChunk` via `getWrapped()`, and type the input as `ChunkAccess` rather than `LevelChunk`.

`ChunkStatus` moved to `net.minecraft.world.level.chunk.status` and is a class, not an enum.

Not viable: `SerializableChunkData` does not exist in 1.21.1 (that is 1.21.6+). `ChunkSerializer.read` off-thread mutates `PoiManager` and the light engine and fires `ChunkDataEvent.Load`. `addRegionTicket` triggers worldgen and save churn.

## Surface classification and shading

### BlockState (exact 1.21.1 parameter lists)

```java
public MapColor getMapColor(BlockGetter level, BlockPos pos)
public boolean  canOcclude()                                        // no args
public boolean  isSolidRender(BlockGetter level, BlockPos pos)      // still takes args
public boolean  propagatesSkylightDown(BlockGetter level, BlockPos pos)   // 2 args in 1.21.1
public FluidState getFluidState()
public VoxelShape getShape(BlockGetter level, BlockPos pos)
public boolean  is(TagKey<Block> tag)
```

`getMapColor` dispatches through NeoForge's `IBlockExtension#getMapColor(state, level, pos, defaultColor)`, which mods use for position-dependent colour. Always pass a real level and pos, never `EmptyBlockGetter` + `BlockPos.ZERO`. Dynamic-shape blocks such as powder snow have no cached shape for the same reason.

`isSolidRender == canOcclude() && occlusionShape is a full block`.

### MapColor

`MATERIAL_COLORS` has 64 slots with 62 populated (ids 0–61, `NONE` through `GLOW_LICHEN`). Fields `public final int id` and `public final int col`. `MapColor.NONE` is identity-comparable.

```java
public int  calculateRGBColor(MapColor.Brightness brightness)   // returns ABGR despite the name
public byte getPackedId(MapColor.Brightness brightness)         // id << 2 | brightness.id & 3
```

`calculateARGBColor` does not exist. **`calculateRGBColor` emits ABGR**, because it feeds `NativeImage.setPixelRGBA`. `VanillaMapPalette.argb` deliberately packs true ARGB for `BufferedImage.TYPE_INT_ARGB`; mirroring vanilla there would swap red and blue.

`Brightness`: `LOW(0, 180)`, `NORMAL(1, 220)`, `HIGH(2, 255)`, `LOWEST(3, 135)`. `LOWEST` is only used for explorer-map outlines, never terrain.

### Vanilla shading algorithm

In `MapItem#update`, **not** `MapItemSavedData`. Literal decompiled selection (`MapItem.java:169-192`):

```java
l2 /= i * i;                       // i = blocksPerPixel; INTEGER division
MapColor mapcolor = Iterables.getFirst(Multisets.copyHighestCountFirst(multiset), MapColor.NONE);
if (mapcolor == MapColor.WATER) {
    double d2 = (double)l2 * 0.1 + (double)(k1 + l1 & 1) * 0.2;
    if (d2 < 0.5)      brightness = HIGH;
    else if (d2 > 0.9) brightness = LOW;
    else               brightness = NORMAL;
} else {
    double d3 = (d1 - d0) * 4.0 / (double)(i + 4) + ((double)(k1 + l1 & 1) - 0.5) * 0.4;
    if (d3 > 0.6)       brightness = HIGH;
    else if (d3 < -0.6) brightness = LOW;
    else                brightness = NORMAL;
}
d0 = d1;
```

Structural details that matter for matching it:

- Heightmap is `Heightmap.Types.WORLD_SURFACE`, and the walk starts at `getHeight(...) + 1`.
- `d0` is the previous **row** in the Z loop, reset once per X column. The gradient runs along +Z (south) and each column is independent.
- The inner loop starts at `l1 = i1 - j1 - 1` — one row early. That extra northern row exists purely to seed `d0` and is discarded by the write guard. **This is why the sampled buffer needs a real padding row on the -Z edge.**
- Depth shading is gated on `mapcolor == MapColor.WATER`, so **lava uses slope shading**.
- Dominant colour ties break by first insertion order.

### Classifying transparent structures

No single vanilla flag exists, and tags are unreliable as a primary rule: `IMPERMEABLE` excludes ice and glass panes; `SNOW` includes the fully opaque snow block; `WOOL_CARPETS` excludes moss carpet.

Geometry works. Ground truth:

| Block | `canOcclude` | Shape height |
| --- | --- | --- |
| glass, stained glass, ice, frosted ice | false | 1.0 |
| packed ice, blue ice, snow block | **true** | 1.0 |
| carpet, moss carpet | true* | 1/16 |
| snow layer *n* | true* | 2n/16 |
| leaves | false | 1.0 |

\* non-full occlusion shape, so `isSolidRender` is still false.

Order: fluid → empty shape (decoration) → thin (`minY≈0 && maxY<=0.25`) → `isSolidRender` (opaque) → leaves tag (foliage) → full-height and `!canOcclude` (transparent structure) → fallback.

**Root cause of the glass-roof bug:** plain `minecraft:glass` is registered with no `.mapColor(...)`, so it reports `MapColor.NONE` and vanilla's `while (getMapColor(...) == MapColor.NONE)` walk falls straight through it. Stained glass *does* set a map colour and stops the walk. Terminating on classification rather than on colour is the fix.

## Heightmaps

Six `Heightmap.Types`. On a `FULL` chunk, `FINAL_HEIGHTMAPS` are live and persisted: `WORLD_SURFACE`, `OCEAN_FLOOR`, `MOTION_BLOCKING`, `MOTION_BLOCKING_NO_LEAVES`. The two `_WG` variants are worldgen-only and gone by FULL.

Never request a `_WG` variant on a live chunk: `ChunkAccess#getHeight` silently calls `primeHeightmaps`, a full-column rescan, instead of failing.

`getHeight` returns the Y of the highest matching block, which is why the walk starts at `+ 1`.

## Dimensions and height

`DimensionType` is a record: `hasCeiling()`, `minY()`, `height()`, `logicalHeight()`. Reached via `serverLevel.dimensionType()`.

`getMinBuildHeight()` / `getMaxBuildHeight()` both exist and are **not** deprecated in 1.21.1. The rename to `getMinY()`/`getMaxY()` is 1.21.2+.

## Players and levels

```java
server.getPlayerList().getPlayers()   // live unmodifiable VIEW — copy on the server thread
server.getAllLevels()                 // live view over a LinkedHashMap; do not cache
player.getUUID()
player.getGameProfile().getName()     // raw account name
player.getX() / getY() / getZ()       // final, primitive, no allocation
player.serverLevel().dimension().location().toString()
player.isSpectator() / isCreative() / isInvisible()
```

`getPlayers()` is a live view over a plain `ArrayList` mutated on the server thread, so off-thread iteration can throw `ConcurrentModificationException` or read a torn position.

Prefer `getGameProfile().getName()` over `getDisplayName()`: the latter dispatches through `EventHooks.getPlayerDisplayName` and appends team prefixes, so it is mod-mutable and unsuitable as a stable identity.

**There is no vanilla or NeoForge vanish API.** Build the privacy filter from `isSpectator()` / `isCreative()` / `isInvisible()` plus a mod-owned opt-out list.

### ResourceLocation

The `new ResourceLocation(...)` constructors are private/removed in 1.21.1 and will not compile.

```java
ResourceLocation.parse(String)      // throws ResourceLocationException
ResourceLocation.tryParse(String)   // returns null
ResourceKey.create(Registries.DIMENSION, id)
```

Use `tryParse` for anything reaching the HTTP surface; `parse` throws a `RuntimeException` that would surface as a 500.

### World identifier

No vanilla value is both unique and stable: `getLevelName()` collides ("New World") and is renameable, the seed is shared by any world generated from it, `LevelStorageAccess#getLevelId()` needs an access transformer, and 1.21.1 stores no world UUID.

So persist a generated UUID at `server.getWorldPath(LevelResource.ROOT).normalize()/cartography/world-id`. Note `getWorldPath(ROOT)` returns `<save>/.`, so `normalize()` before taking `getFileName()`.

### Dedicated vs integrated

`ServerStartingEvent` fires for `IntegratedServer` too, so an unguarded mod starts its HTTP server in singleplayer. `server.isDedicatedServer()` is the authoritative check and is safe in common code, unlike `FMLEnvironment.dist`.

## Tile image encoding

Stock Java 21 has **no WebP writer**. Every WebP option on Maven Central bundles platform natives.

Measured on synthetic tiles built from this project's real palette: **lossy WebP q85 was 45% larger than indexed PNG** (17,834 vs 9,456 bytes), because a DCT codec is the wrong tool for flat-shaded palette art. Ordering held across every content type tested: indexed PNG < ARGB PNG < WebP q85 < JPEG q85.

The plan's "webp quality 85" was written for photographic tiles and does not apply here.

`packedId = mapColorId << 2 | brightness` yields 248 values with no collisions, so it is already a valid PNG palette index. Build one `IndexColorModel` and write indices straight into the raster — never through `Graphics2D.drawImage`, which re-matches each pixel to the nearest entry and is both slower and lossy.

## Unverified / needs runtime check

Source-level verification only; the following were not confirmed on a running server.

- **The mixin has never been observed applying at runtime.** Compilation and jar packaging are confirmed; actual transformation of `LevelChunk` in a launched server is not.
- **No performance measurement** of injecting into `LevelChunk#setBlockState`. The hot-path concern is inferred from call-site density, not profiled. Benchmark with large fluid bodies and bulk edits.
- **Third-party bulk editors** (WorldEdit, FAWE, Axiom) are assumed to route through `Level#setBlock`. FAWE is known to write chunk sections directly on some platforms and may bypass the mixin entirely.
- **`THIN_OVERLAY_MAX_HEIGHT = 0.25` is a design choice**, not a vanilla constant. Snow layers 1–2 fall below it, 3+ above.
- The classifier composition is a synthesis; vanilla has no equivalent. Every individual API it calls is verified.
- **Modded block behaviour** under the classifier assumes authors use `.noOcclusion()` and accurate `getShape` overrides. Non-conforming blocks land in `UNKNOWN_FALLBACK`.
- Plain `minecraft:glass` reporting `MapColor.NONE` is inferred from the absence of a `.mapColor(...)` call in its registration, not observed at runtime.
- **Rendered output has not been diffed against an in-game map item**, so the visual delta from the remaining fidelity gaps is unmeasured.
- WebP measurements used synthetic Voronoi tiles on Windows/JDK 23; JDK 21 was bracketed by 17 and 23, not run directly. Encode timings are hardware-specific.
- `ChunkEvent.Load` firing more than once for the same `ChunkPos` within a session (unload/reload churn) was not traced; the debounce likely absorbs it.
- `getCurrentSmoothedTickTime()` under-reports MSPT for roughly the first 20–30 ticks after start, inferred from the EMA formula rather than measured.
