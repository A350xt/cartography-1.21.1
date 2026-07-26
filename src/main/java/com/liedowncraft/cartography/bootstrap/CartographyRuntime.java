package com.liedowncraft.cartography.bootstrap;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.core.TileGrid;
import com.liedowncraft.cartography.core.TileGridNormalization;
import com.liedowncraft.cartography.core.TileMath;
import com.liedowncraft.cartography.core.TilesetVersionCalculator;
import com.liedowncraft.cartography.render.TileDownsampler;
import com.liedowncraft.cartography.render.TileImageCodec;
import com.liedowncraft.cartography.render.VanillaMapTileRenderer;
import com.liedowncraft.cartography.scheduler.DirtyChunkTracker;
import com.liedowncraft.cartography.scheduler.RenderScheduler;
import com.liedowncraft.cartography.snapshot.SampledMapBuffer;
import com.liedowncraft.cartography.snapshot.WorldSnapshotProvider;
import com.liedowncraft.cartography.storage.FileSystemTileStore;
import com.liedowncraft.cartography.storage.StoredTile;
import com.liedowncraft.cartography.web.CartographyHttpServer;
import com.liedowncraft.cartography.web.HealthStatus;
import com.liedowncraft.cartography.web.LocationPrivacyPolicy;
import com.liedowncraft.cartography.web.MapManifest;
import com.liedowncraft.cartography.web.PlayerMarker;
import com.liedowncraft.cartography.web.TilePath;
import com.liedowncraft.cartography.web.TileResponse;

/**
 * Owns the tile pipeline: config, tile store, scheduler, dirty tracking and the HTTP surface
 * (technical plan v2.0, section 3).
 *
 * <p>Deliberately free of Minecraft types apart from the injected {@link WorldSnapshotProvider}, so
 * the whole tile lifecycle is testable without launching a server.
 */
public final class CartographyRuntime implements AutoCloseable {
    /**
     * Upper bound on tiles visited while expanding one cache miss. Keeps a single request for a
     * low-zoom tile from walking the whole pyramid beneath it on an HTTP thread.
     */
    private static final int MAX_MISS_EXPANSION = 256;

    private final CartographySettings settings;
    private final String worldId;
    private final String tilesetVersion;
    private final FileSystemTileStore tileStore;
    private final VanillaMapTileRenderer renderer;
    private final WorldSnapshotProvider snapshotProvider;
    private final LocationPrivacyPolicy privacyPolicy;
    private final DirtyChunkTracker dirtyChunkTracker;
    private final byte[] pendingTile;
    private final String pendingTileMimeType;
    private final RenderScheduler scheduler;
    private final CartographyHttpServer httpServer;
    private final ConcurrentMap<String, PlayerMarker> markers = new ConcurrentHashMap<>();

    private final AtomicLong tileCacheHits = new AtomicLong();
    private final AtomicLong tileCacheMisses = new AtomicLong();
    private volatile double lastSnapshotMillis;
    private volatile double lastRenderMillis;
    private volatile double lastTileWriteMillis;

    /**
     * Observed extent of rendered tiles. The published normalization offset derives from this, so it
     * grows as players explore.
     */
    private volatile TileGridNormalization normalization = new TileGridNormalization(0, 0, 0, 0);
    private boolean normalizationSeeded;

    public CartographyRuntime(CartographySettings settings, String worldId, WorldSnapshotProvider snapshotProvider)
            throws IOException {
        this.settings = settings;
        this.worldId = worldId;
        this.tilesetVersion = TilesetVersionCalculator.calculate(
                worldId, settings.renderer().defaultDimension(), settings.renderer());
        this.tileStore = new FileSystemTileStore(settings.tileRoot());
        this.renderer = new VanillaMapTileRenderer();
        this.snapshotProvider = snapshotProvider;
        this.privacyPolicy = new LocationPrivacyPolicy(settings.markers());
        this.dirtyChunkTracker = new DirtyChunkTracker(settings.scheduler().dirtyDebounceSeconds() * 1000L);
        this.pendingTile = renderer.renderPendingTile(settings.renderer());
        this.pendingTileMimeType = TileImageCodec.sniffMimeType(pendingTile);
        this.scheduler = new RenderScheduler(settings.scheduler());
        this.httpServer = new CartographyHttpServer(this, settings.web().bindHost(), settings.web().port());
    }

    public static CartographyRuntime startForTests(CartographySettings settings) throws IOException {
        return startForTests(settings, unsupportedSnapshotProvider());
    }

    public static CartographyRuntime startForTests(CartographySettings settings, WorldSnapshotProvider snapshotProvider)
            throws IOException {
        CartographyRuntime runtime = new CartographyRuntime(settings, "test-world", snapshotProvider);
        runtime.start();
        return runtime;
    }

    public void start() throws IOException {
        scheduler.start(this::renderJob, this::refreshAncestor);
        tileStore.writeMetadata(tilesetVersion, metadataJson());
        if (settings.web().enabled()) {
            httpServer.start();
        }
    }

    public URI baseUri() {
        return httpServer.baseUri();
    }

    public String tilesetVersion() {
        return tilesetVersion;
    }

    public String worldId() {
        return worldId;
    }

    public int queueDepth() {
        return scheduler.queueDepth();
    }

    public CartographySettings settings() {
        return settings;
    }

    public MapManifest manifest() {
        RendererProfile profile = settings.renderer();
        return new MapManifest(
                MapManifest.CRS,
                worldId,
                profile.defaultDimension(),
                profile.dimensions(),
                MapManifest.DATA_COORDINATE,
                profile.tileCoordinateMode(),
                profile.tileGrid(),
                normalization,
                profile.profileId(),
                tilesetVersion,
                profile.rendererCodeVersion(),
                profile.materialTableVersion(),
                profile.configuredPackSignature(),
                profile.format(),
                profile.quality(),
                TilePath.template() + "." + profile.fileExtension(),
                settings.markers().mode(),
                settings.markers().pollIntervalMs(),
                settings.web().pendingTileRetryMs());
    }

    /**
     * Serves a tile, queueing a render on a miss.
     *
     * <p>A miss returns the pending placeholder with 200 rather than 404: the client keeps a valid
     * image in the grid and retries, instead of rendering an error cell.
     */
    public TileResponse tileResponse(TilePath tilePath) throws IOException {
        RendererProfile profile = settings.renderer();
        if (!tilesetVersion.equals(tilePath.tilesetVersion()) || !worldId.equals(tilePath.world())) {
            // A request under a stale namespace must never be answered with current pixels.
            return new TileResponse(404, new byte[0], "text/plain", Map.of("Cache-Control", "no-store"));
        }

        Optional<StoredTile> storedTile = tileStore.read(tilesetVersion, tilePath.tile(), profile.fileExtension());
        if (storedTile.isPresent()) {
            tileCacheHits.incrementAndGet();
            StoredTile ready = storedTile.orElseThrow();
            return new TileResponse(
                    200,
                    ready.bytes(),
                    ready.mimeType(),
                    Map.of("Cache-Control", "public, max-age=31536000, immutable"));
        }

        tileCacheMisses.incrementAndGet();
        enqueueRenderFor(tilePath.tile());
        return new TileResponse(
                200,
                pendingTile,
                pendingTileMimeType,
                Map.of(
                        "Cache-Control", "no-store",
                        "X-Cartography-Tile-State", "pending",
                        "Retry-After", Integer.toString(Math.max(1, settings.web().pendingTileRetryMs() / 1000))));
    }

    /**
     * A tile below max zoom is produced by downsampling, so a miss there queues the max-zoom work
     * that will eventually feed it rather than trying to sample the whole span at once.
     *
     * <p>Children that are already rendered are skipped. Without that check, a client polling a
     * low-zoom tile would re-queue the same max-zoom metatiles on every retry and keep the workers
     * too busy to ever run the downsample pass that would satisfy the request.
     *
     * <p>Descent is bounded by {@link #MAX_MISS_EXPANSION}. A zoom-0 miss covers every tile in the
     * pyramid beneath it — over 65,000 leaves at max zoom eight — and walking all of them would let
     * one unauthenticated request stat tens of thousands of files on an HTTP thread. Partial descent
     * is fine: what is queued renders, its ancestors are marked dirty, and a later request continues
     * from there.
     */
    private void enqueueRenderFor(TileCoordinate tile) throws IOException {
        expandMiss(tile, new java.util.ArrayDeque<>(List.of(tile)), 0);
    }

    /**
     * Breadth-first descent from a missing tile toward max zoom, capped in total work.
     *
     * @return the number of tiles visited
     */
    private int expandMiss(TileCoordinate root, java.util.Deque<TileCoordinate> frontier, int visited) throws IOException {
        RendererProfile profile = settings.renderer();
        String extension = profile.fileExtension();

        while (!frontier.isEmpty() && visited < MAX_MISS_EXPANSION) {
            TileCoordinate tile = frontier.removeFirst();
            visited++;

            if (tile.zoom() >= profile.maxZoom()) {
                scheduler.enqueue(TileMath.groupIntoMetatile(tilesetVersion, tile, profile));
                continue;
            }

            // Queue the parent for downsampling once its children exist.
            scheduler.markAncestorDirty(tile);
            for (TileCoordinate child : TileMath.childrenOf(tile, profile.maxZoom())) {
                if (!tileStore.exists(tilesetVersion, child, extension)) {
                    frontier.addLast(child);
                }
            }
        }
        return visited;
    }

    public List<PlayerMarker> markers(String dimension) {
        List<PlayerMarker> visible = privacyPolicy.filterForPublicMap(markers.values(), System.currentTimeMillis());
        List<PlayerMarker> forDimension = new ArrayList<>();
        for (PlayerMarker marker : visible) {
            if (marker.dimension().equals(dimension)) {
                forDimension.add(marker);
            }
        }
        return forDimension;
    }

    public HealthStatus health() {
        return new HealthStatus(
                true,
                tilesetVersion,
                scheduler.queueDepth(),
                scheduler.ancestorBacklog(),
                dirtyChunkTracker.pendingCount(),
                scheduler.isPaused(),
                scheduler.currentTps(),
                scheduler.renderedJobs(),
                scheduler.failedJobs(),
                scheduler.droppedJobs(),
                scheduler.refreshedAncestors(),
                tileCacheHits.get(),
                tileCacheMisses.get(),
                lastSnapshotMillis,
                lastRenderMillis,
                lastTileWriteMillis,
                scheduler.lastFailure());
    }

    /** Records a dirty chunk; it is debounced and only becomes a render job in {@link #drainDirtyChunks}. */
    public void markDirtyChunk(String dimension, int chunkX, int chunkZ) {
        dirtyChunkTracker.markDirty(dimension, chunkX, chunkZ, System.currentTimeMillis());
    }

    /**
     * Converts settled dirty chunks into metatile jobs (plan section 6.2). Called from the server
     * tick so debouncing is driven by real time.
     *
     * @return number of metatile jobs enqueued
     */
    public int drainDirtyChunks() {
        return drainDirtyChunks(System.currentTimeMillis());
    }

    int drainDirtyChunks(long nowMillis) {
        RendererProfile profile = settings.renderer();
        List<MetatileJob> jobs = new ArrayList<>();
        for (DirtyChunkTracker.DirtyChunk chunk : dirtyChunkTracker.drainSettled(nowMillis)) {
            for (TileCoordinate tile : TileMath.dirtyChunkToTiles(chunk.dimension(), chunk.chunkX(), chunk.chunkZ(), profile)) {
                MetatileJob job = TileMath.groupIntoMetatile(tilesetVersion, tile, profile);
                // Distinct dirty chunks routinely land in the same metatile; collapse before enqueue.
                if (!jobs.contains(job)) {
                    jobs.add(job);
                }
            }
        }

        for (MetatileJob job : jobs) {
            scheduler.enqueue(job);
        }
        return jobs.size();
    }

    public void setCurrentTps(double tps) {
        scheduler.setCurrentTps(tps);
    }

    public ClassLoader classLoader() {
        return getClass().getClassLoader();
    }

    public void upsertMarker(PlayerMarker marker) {
        markers.put(marker.uuid(), marker);
    }

    public void removeMarker(String uuid) {
        markers.remove(uuid);
    }

    /** Drops markers for players who are no longer online, so the map cannot show a stale ghost. */
    public void retainMarkers(java.util.Set<String> onlineUuids) {
        markers.keySet().retainAll(onlineUuids);
    }

    @Override
    public void close() {
        httpServer.close();
        scheduler.close();
    }

    /**
     * Renders one metatile: sample with padding, rasterize, crop, cut child tiles, mark ancestors.
     */
    private void renderJob(MetatileJob job) throws IOException {
        RendererProfile profile = settings.renderer();

        long snapshotStart = System.nanoTime();
        SampledMapBuffer snapshot = snapshotProvider.capture(job, profile);
        lastSnapshotMillis = millisSince(snapshotStart);

        long renderStart = System.nanoTime();
        BufferedImage metatileImage = renderer.renderImage(snapshot);
        lastRenderMillis = millisSince(renderStart);

        int tileSize = profile.tileSize();
        long writeStart = System.nanoTime();
        for (TileCoordinate tile : job.tiles()) {
            int offsetX = (tile.x() - job.startX()) * tileSize;
            int offsetY = (tile.y() - job.startY()) * tileSize;
            if (offsetX + tileSize > metatileImage.getWidth() || offsetY + tileSize > metatileImage.getHeight()) {
                throw new IOException("Rendered metatile is smaller than its tile grid; check snapshot padding");
            }

            byte[] bytes = renderer.encodeImage(metatileImage.getSubimage(offsetX, offsetY, tileSize, tileSize));
            tileStore.write(tilesetVersion, tile, bytes, profile.fileExtension());
            observeRenderedTile(tile);
            markAncestorsDirty(tile);
        }
        lastTileWriteMillis = millisSince(writeStart);
    }

    /** Rebuilds one low-zoom tile from its children (plan section 6.4). */
    private void refreshAncestor(TileCoordinate ancestor) throws IOException {
        RendererProfile profile = settings.renderer();
        List<TileCoordinate> children = TileMath.childrenOf(ancestor, profile.maxZoom());
        if (children.isEmpty()) {
            return;
        }

        BufferedImage[] quadrants = new BufferedImage[4];
        boolean anyChildPresent = false;
        for (int index = 0; index < children.size(); index++) {
            Optional<StoredTile> child = tileStore.read(tilesetVersion, children.get(index), profile.fileExtension());
            if (child.isPresent()) {
                quadrants[index] = ImageIO.read(new ByteArrayInputStream(child.orElseThrow().bytes()));
                anyChildPresent = true;
            }
        }

        if (!anyChildPresent) {
            // Nothing to downsample from yet; leave the tile absent so it reads as pending.
            return;
        }

        BufferedImage downsampled = TileDownsampler.downsample(quadrants, profile.tileSize());
        tileStore.write(tilesetVersion, ancestor, renderer.encodeImage(downsampled), profile.fileExtension());
        observeRenderedTile(ancestor);

        // Cascade upward so a change at max zoom eventually reaches zoom 0.
        TileMath.parentOf(ancestor, profile.minZoom()).ifPresent(scheduler::markAncestorDirty);
    }

    private void markAncestorsDirty(TileCoordinate tile) {
        for (TileCoordinate ancestor : TileMath.ancestorChain(tile, settings.renderer().minZoom())) {
            scheduler.markAncestorDirty(ancestor);
        }
    }

    /** Tracks the signed extent at max zoom so the manifest can publish a normalization offset. */
    private synchronized void observeRenderedTile(TileCoordinate tile) {
        if (tile.zoom() != settings.renderer().maxZoom()) {
            return;
        }

        if (!normalizationSeeded) {
            normalization = new TileGridNormalization(tile.x(), tile.y(), tile.x(), tile.y());
            normalizationSeeded = true;
            return;
        }

        normalization = new TileGridNormalization(
                Math.min(normalization.minSignedTileX(), tile.x()),
                Math.min(normalization.minSignedTileY(), tile.y()),
                Math.max(normalization.maxSignedTileX(), tile.x()),
                Math.max(normalization.maxSignedTileY(), tile.y()));
    }

    /** Tileset metadata written beside the tiles (plan appendix A.2). */
    private String metadataJson() {
        RendererProfile profile = settings.renderer();
        TileGrid grid = profile.tileGrid();
        return """
                {
                  "tilesetVersion": "%s",
                  "world": "%s",
                  "profile": "%s",
                  "resourcePackHash": "%s",
                  "rendererVersion": "%s",
                  "materialTableHash": "%s",
                  "format": "%s",
                  "quality": %d,
                  "tileGrid": {
                    "mode": "%s",
                    "tileSize": %d,
                    "maxZoom": %d,
                    "minZoom": %d,
                    "pixelsPerBlockAtMaxZoom": %d,
                    "tileOriginX": %d,
                    "tileOriginZ": %d
                  }
                }
                """.formatted(
                tilesetVersion,
                worldId,
                profile.profileId(),
                profile.configuredPackSignature(),
                profile.rendererCodeVersion(),
                profile.materialTableVersion(),
                profile.format(),
                profile.quality(),
                profile.tileCoordinateMode().manifestValue(),
                grid.tileSize(),
                grid.maxZoom(),
                grid.minZoom(),
                grid.pixelsPerBlockAtMaxZoom(),
                grid.tileOriginX(),
                grid.tileOriginZ());
    }

    private static double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static WorldSnapshotProvider unsupportedSnapshotProvider() {
        return (job, profile) -> {
            throw new UnsupportedOperationException("No world snapshot provider configured for this test runtime");
        };
    }
}
