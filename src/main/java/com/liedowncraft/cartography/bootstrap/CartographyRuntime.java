package com.liedowncraft.cartography.bootstrap;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import com.liedowncraft.cartography.render.VanillaMapTileRenderer;
import com.liedowncraft.cartography.render.TileImageCodec;
import com.liedowncraft.cartography.snapshot.MainThreadWorldSnapshotProvider;
import com.liedowncraft.cartography.snapshot.SampledMapBuffer;
import com.liedowncraft.cartography.snapshot.WorldSnapshotProvider;
import com.liedowncraft.cartography.scheduler.RenderScheduler;
import com.liedowncraft.cartography.storage.FileSystemTileStore;
import com.liedowncraft.cartography.storage.StoredTile;
import com.liedowncraft.cartography.web.CartographyHttpServer;
import com.liedowncraft.cartography.web.HealthStatus;
import com.liedowncraft.cartography.web.MapManifest;
import com.liedowncraft.cartography.web.PlayerMarker;
import com.liedowncraft.cartography.web.TileResponse;

import net.minecraft.server.MinecraftServer;

public final class CartographyRuntime implements AutoCloseable {
    private final CartographySettings settings;
    private final String tilesetVersion;
    private final FileSystemTileStore tileStore;
    private final VanillaMapTileRenderer renderer;
    private final WorldSnapshotProvider snapshotProvider;
    private final byte[] pendingTile;
    private final String pendingTileMimeType;
    private final RenderScheduler scheduler;
    private final CartographyHttpServer httpServer;
    private final ConcurrentMap<String, PlayerMarker> markers = new ConcurrentHashMap<>();

    public CartographyRuntime(CartographySettings settings, MinecraftServer server) throws IOException {
        this(settings, new MainThreadWorldSnapshotProvider(server));
    }

    public CartographyRuntime(CartographySettings settings, WorldSnapshotProvider snapshotProvider) throws IOException {
        this.settings = settings;
        this.tilesetVersion = TilesetVersionCalculator.calculate(settings.renderer());
        this.tileStore = new FileSystemTileStore(settings.tileRoot());
        this.renderer = new VanillaMapTileRenderer();
        this.snapshotProvider = snapshotProvider;
        this.pendingTile = renderer.renderPendingTile(settings.renderer());
        this.pendingTileMimeType = TileImageCodec.sniffMimeType(pendingTile);
        this.scheduler = new RenderScheduler(settings.scheduler());
        this.httpServer = new CartographyHttpServer(this, settings.web().bindHost(), settings.web().port());
    }

    public static CartographyRuntime startForTests(CartographySettings settings) throws IOException {
        CartographyRuntime runtime = new CartographyRuntime(settings, unsupportedSnapshotProvider());
        runtime.start();
        return runtime;
    }

    public static CartographyRuntime startForTests(CartographySettings settings, WorldSnapshotProvider snapshotProvider) throws IOException {
        CartographyRuntime runtime = new CartographyRuntime(settings, snapshotProvider);
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
        SampledMapBuffer metatileSnapshot = snapshotProvider.capture(job, settings.renderer());
        BufferedImage metatileImage = renderer.renderImage(metatileSnapshot);
        int tileSize = settings.renderer().tileSize();
        for (TileCoordinate tile : job.tiles()) {
            int tileOffsetX = (tile.x() - job.startX()) * tileSize;
            int tileOffsetY = (tile.y() - job.startY()) * tileSize;
            byte[] bytes = renderer.encodeImage(copyTileImage(metatileImage, tileOffsetX, tileOffsetY, tileSize));
            tileStore.write(job.tilesetVersion(), tile, bytes);
            invalidateAncestors(tile);
        }
    }

    private void invalidateAncestors(TileCoordinate tile) throws IOException {
        for (TileCoordinate ancestor : TileMath.ancestorChain(tile, settings.renderer().minZoom())) {
            tileStore.delete(tilesetVersion, ancestor);
        }
    }

    private static WorldSnapshotProvider unsupportedSnapshotProvider() {
        return (job, profile) -> {
            throw new UnsupportedOperationException("No world snapshot provider configured for this test runtime");
        };
    }

    private BufferedImage copyTileImage(BufferedImage metatileImage, int tileOffsetX, int tileOffsetY, int tileSize) {
        BufferedImage tileImage = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = tileImage.createGraphics();
        try {
            graphics.drawImage(
                    metatileImage,
                    0,
                    0,
                    tileSize,
                    tileSize,
                    tileOffsetX,
                    tileOffsetY,
                    tileOffsetX + tileSize,
                    tileOffsetY + tileSize,
                    null);
        } finally {
            graphics.dispose();
        }
        return tileImage;
    }
}
