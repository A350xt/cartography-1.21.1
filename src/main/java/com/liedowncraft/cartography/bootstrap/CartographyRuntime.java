package com.liedowncraft.cartography.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.core.TileMath;
import com.liedowncraft.cartography.core.TilesetVersionCalculator;
import com.liedowncraft.cartography.render.PatternTileRenderer;
import com.liedowncraft.cartography.render.TileImageCodec;
import com.liedowncraft.cartography.scheduler.RenderScheduler;
import com.liedowncraft.cartography.storage.FileSystemTileStore;
import com.liedowncraft.cartography.storage.StoredTile;
import com.liedowncraft.cartography.web.CartographyHttpServer;
import com.liedowncraft.cartography.web.HealthStatus;
import com.liedowncraft.cartography.web.MapManifest;
import com.liedowncraft.cartography.web.PlayerMarker;
import com.liedowncraft.cartography.web.TileResponse;

public final class CartographyRuntime implements AutoCloseable {
    private final CartographySettings settings;
    private final String tilesetVersion;
    private final FileSystemTileStore tileStore;
    private final PatternTileRenderer renderer;
    private final byte[] pendingTile;
    private final String pendingTileMimeType;
    private final RenderScheduler scheduler;
    private final CartographyHttpServer httpServer;
    private final ConcurrentMap<String, PlayerMarker> markers = new ConcurrentHashMap<>();

    public CartographyRuntime(CartographySettings settings) throws IOException {
        this.settings = settings;
        this.tilesetVersion = TilesetVersionCalculator.calculate(settings.renderer());
        this.tileStore = new FileSystemTileStore(settings.tileRoot());
        this.renderer = new PatternTileRenderer();
        this.pendingTile = renderer.renderPendingTile(settings.renderer());
        this.pendingTileMimeType = TileImageCodec.sniffMimeType(pendingTile);
        this.scheduler = new RenderScheduler(settings.scheduler());
        this.httpServer = new CartographyHttpServer(this, settings.web().bindHost(), settings.web().port());
    }

    public static CartographyRuntime startForTests(CartographySettings settings) throws IOException {
        CartographyRuntime runtime = new CartographyRuntime(settings);
        runtime.start();
        return runtime;
    }

    public void start() {
        scheduler.start(this::renderJob);
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

    public int queueDepth() {
        return scheduler.queueDepth();
    }

    public MapManifest manifest() {
        return new MapManifest(
                settings.renderer().tileSize(),
                settings.renderer().minZoom(),
                settings.renderer().maxZoom(),
                settings.renderer().pixelsPerBlockAtMaxZoom(),
                settings.renderer().dimensions(),
                settings.renderer().defaultDimension(),
                tilesetVersion,
                "/tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp",
                settings.markers().mode(),
                settings.web().pendingTileRetryMs());
    }

    public TileResponse tileResponse(String requestedTilesetVersion, TileCoordinate tile) throws IOException {
        if (!tilesetVersion.equals(requestedTilesetVersion)) {
            return new TileResponse(404, new byte[0], "text/plain", Map.of());
        }

        Optional<StoredTile> storedTile = tileStore.read(requestedTilesetVersion, tile);
        if (storedTile.isPresent()) {
            StoredTile ready = storedTile.orElseThrow();
            return new TileResponse(200, ready.bytes(), ready.mimeType(), Map.of("Cache-Control", "public, max-age=60"));
        }

        scheduler.enqueue(TileMath.groupIntoMetatile(tilesetVersion, tile, settings.renderer()));
        return new TileResponse(
                200,
                pendingTile,
                pendingTileMimeType,
                Map.of(
                        "Cache-Control", "no-store",
                        "X-Cartography-Tile-State", "pending",
                        "Retry-After", Integer.toString(Math.max(1, settings.web().pendingTileRetryMs() / 1000))));
    }

    public List<PlayerMarker> markers(String dimension) {
        if (!settings.markers().enabled()) {
            return List.of();
        }

        List<PlayerMarker> visibleMarkers = new ArrayList<>();
        for (PlayerMarker marker : markers.values()) {
            if (marker.dimension().equals(dimension)) {
                visibleMarkers.add(marker);
            }
        }
        return visibleMarkers;
    }

    public HealthStatus health() {
        return new HealthStatus(true, scheduler.queueDepth(), scheduler.isPaused(), tilesetVersion);
    }

    public void markDirtyChunk(String dimension, int chunkX, int chunkZ) {
        for (TileCoordinate tile : TileMath.dirtyChunkToTiles(dimension, chunkX, chunkZ, settings.renderer())) {
            scheduler.enqueue(TileMath.groupIntoMetatile(tilesetVersion, tile, settings.renderer()));
        }
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

    @Override
    public void close() {
        httpServer.close();
        scheduler.close();
    }

    private void renderJob(MetatileJob job) throws IOException {
        for (TileCoordinate tile : job.tiles()) {
            byte[] bytes = renderer.renderTile(tile, settings.renderer());
            tileStore.write(job.tilesetVersion(), tile, bytes);
            invalidateAncestors(tile);
        }
    }

    private void invalidateAncestors(TileCoordinate tile) throws IOException {
        for (TileCoordinate ancestor : TileMath.ancestorChain(tile, settings.renderer().minZoom())) {
            tileStore.delete(tilesetVersion, ancestor);
        }
    }
}
