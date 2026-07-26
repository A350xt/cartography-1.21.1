package com.liedowncraft.cartography.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.core.TileGrid;
import com.liedowncraft.cartography.core.TileGridNormalization;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Embedded tile and API server (technical plan v2.0, sections 7.4 and 12.2).
 *
 * <p>Cache policy follows the plan: versioned tile URLs are immutable and cached for a year, while
 * the manifest is revalidated every time so a client picks up a new tileset version promptly.
 */
public final class CartographyHttpServer implements AutoCloseable {
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";
    private static final String API_PREFIX = "/api/cartography/v1";

    private final CartographyRuntime runtime;
    private final HttpServer server;

    public CartographyHttpServer(CartographyRuntime runtime, String host, int port) throws IOException {
        this.runtime = runtime;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        registerContexts();
    }

    public void start() {
        server.start();
    }

    public URI baseUri() {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void registerContexts() {
        server.createContext("/manifest.json", this::handleManifest);
        server.createContext(API_PREFIX + "/tilegrids/default/manifest", this::handleManifest);
        server.createContext("/healthz", exchange -> writeJson(exchange, healthJson(runtime.health()), "no-store"));
        server.createContext(API_PREFIX + "/live/players", this::handleMarkers);
        server.createContext("/markers", this::handleMarkers);
        server.createContext("/tiles/raster", this::handleTiles);
        server.createContext("/", this::handleStaticAsset);
    }

    private void handleManifest(HttpExchange exchange) throws IOException {
        MapManifest manifest = runtime.manifest();
        String body = manifestJson(manifest);
        // Weak validator on the tileset version: a client that already has the current tileset can
        // skip the payload, but it can never keep serving a stale one.
        String etag = "\"" + manifest.tilesetVersion() + "\"";

        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("ETag", etag);
            headers.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        writeBytes(
                exchange,
                200,
                "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("Cache-Control", "no-cache", "ETag", etag));
    }

    private void handleMarkers(HttpExchange exchange) throws IOException {
        String dimension = queryParameter(exchange.getRequestURI(), "dimension")
                .orElseGet(() -> runtime.manifest().defaultDimension());
        writeJson(exchange, markerJson(runtime.markers(dimension)), "no-store");
    }

    private void handleTiles(HttpExchange exchange) throws IOException {
        Optional<TilePath> parsed = TilePath.parse(exchange.getRequestURI().getPath());
        if (parsed.isEmpty()) {
            writeBytes(exchange, 404, "text/plain", "tile path not found".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }

        TilePath tilePath = parsed.orElseThrow();
        TileResponse response = runtime.tileResponse(tilePath);
        writeBytes(exchange, response.statusCode(), response.mimeType(), response.body(), response.headers());
    }

    private void handleStaticAsset(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }

        // The bundle is served out of the mod jar, so reject anything that could climb out of it.
        // Decoded first: a percent-encoded traversal only becomes "../" after decoding, so checking
        // the raw text would miss it. Then allowlisted, because enumerating safe characters is more
        // reliable than trying to enumerate every dangerous encoding.
        if (!isSafeAssetPath(path)) {
            writeBytes(exchange, 400, "text/plain", "bad request".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }

        String resourcePath = "web" + path;
        try (InputStream input = runtime.classLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                if ("/index.html".equals(path)) {
                    byte[] fallback = """
                            <!doctype html>
                            <html lang="en">
                              <head><meta charset="utf-8"><title>Cartography</title></head>
                              <body><div id="app">Cartography frontend not built yet. Run ./gradlew buildFrontend.</div></body>
                            </html>
                            """.getBytes(StandardCharsets.UTF_8);
                    writeBytes(exchange, 200, "text/html; charset=utf-8", fallback, Map.of("Cache-Control", "no-store"));
                    return;
                }

                writeBytes(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), Map.of());
                return;
            }

            // Vite emits content-hashed asset filenames, so those are safe to cache immutably.
            String cacheControl = path.startsWith("/assets/") ? IMMUTABLE_CACHE : "no-cache";
            writeBytes(exchange, 200, contentType(path), input.readAllBytes(), Map.of("Cache-Control", cacheControl));
        }
    }

    /**
     * Whether a request path may be resolved against the bundled frontend.
     *
     * <p>Each segment must be a plain filename: letters, digits, dot, underscore or dash, never
     * starting with a dot. That excludes {@code .} and {@code ..} by construction, along with drive
     * letters, UNC prefixes and encoded separators.
     */
    private boolean isSafeAssetPath(String path) {
        String decoded;
        try {
            decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badEscape) {
            return false;
        }

        if (!decoded.startsWith("/") || decoded.length() > 256) {
            return false;
        }

        for (String segment : decoded.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.charAt(0) == '.') {
                return false;
            }

            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                boolean allowed = (character >= 'a' && character <= 'z')
                        || (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9')
                        || character == '_'
                        || character == '-'
                        || character == '.';
                if (!allowed) {
                    return false;
                }
            }
        }
        return true;
    }

    private void writeJson(HttpExchange exchange, String body, String cacheControl) throws IOException {
        writeBytes(
                exchange,
                200,
                "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("Cache-Control", cacheControl));
    }

    private void writeBytes(HttpExchange exchange, int status, String contentType, byte[] body, Map<String, String> extraHeaders)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        extraHeaders.forEach(headers::set);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /** Manifest payload per plan appendix A.2. */
    private String manifestJson(MapManifest manifest) {
        TileGrid grid = manifest.tileGrid();
        TileGridNormalization normalization = manifest.normalization();

        Json json = new Json().object()
                .field("crs", manifest.crs())
                .field("world", manifest.world())
                .field("dimension", manifest.defaultDimension())
                .field("defaultDimension", manifest.defaultDimension())
                .stringArray("dimensions", manifest.dimensions())
                .field("dataCoordinate", manifest.dataCoordinate())
                .field("tileCoordinateMode", manifest.tileCoordinateMode().manifestValue())
                .field("profile", manifest.profile())
                .field("tilesetVersion", manifest.tilesetVersion())
                .field("rendererVersion", manifest.rendererVersion())
                .field("materialTableHash", manifest.materialTableHash())
                .field("resourcePackHash", manifest.resourcePackHash())
                .field("format", manifest.format())
                .field("quality", manifest.quality())
                .field("tileUrlTemplate", manifest.tileUrlTemplate())
                .field("markerMode", manifest.markerMode())
                .field("markerPollIntervalMs", manifest.markerPollIntervalMs())
                .field("pendingTileRetryMs", manifest.pendingTileRetryMs());

        json.objectField("tileGrid")
                .field("mode", manifest.tileCoordinateMode().manifestValue())
                .field("tileSize", grid.tileSize())
                .field("minZoom", grid.minZoom())
                .field("maxZoom", grid.maxZoom())
                .field("pixelsPerBlockAtMaxZoom", grid.pixelsPerBlockAtMaxZoom())
                .field("blocksPerTileAtMaxZoom", grid.blocksPerTileAtMaxZoom())
                .field("tileOriginX", grid.tileOriginX())
                .field("tileOriginZ", grid.tileOriginZ())
                .field("minSignedTileX", normalization.minSignedTileX())
                .field("minSignedTileY", normalization.minSignedTileY())
                .field("maxSignedTileX", normalization.maxSignedTileX())
                .field("maxSignedTileY", normalization.maxSignedTileY())
                .field("normalizedOffsetX", normalization.normalizedOffsetX())
                .field("normalizedOffsetY", normalization.normalizedOffsetY())
                .endObject();

        return json.endObject().toString();
    }

    private String markerJson(List<PlayerMarker> markers) {
        StringBuilder payload = new StringBuilder("{\"players\":[");
        for (int index = 0; index < markers.size(); index++) {
            PlayerMarker marker = markers.get(index);
            if (index > 0) {
                payload.append(',');
            }
            payload.append(new Json().object()
                    .field("uuid", marker.uuid())
                    .field("name", marker.name())
                    .field("dimension", marker.dimension())
                    .field("x", marker.x())
                    .field("z", marker.z())
                    .field("updatedAt", marker.updatedAt())
                    .endObject());
        }
        return payload.append("]}").toString();
    }

    /** Health payload doubles as the observability endpoint from plan section 15.2. */
    private String healthJson(HealthStatus status) {
        return new Json().object()
                .field("alive", status.alive())
                .field("tilesetVersion", status.tilesetVersion())
                .field("renderJobQueueDepth", status.queueDepth())
                .field("ancestorDirtyBacklog", status.ancestorDirtyBacklog())
                .field("pendingDirtyChunks", status.pendingDirtyChunks())
                .field("schedulerPaused", status.schedulerPaused())
                .field("currentTps", status.currentTps())
                .field("renderedJobs", status.renderedJobs())
                .field("failedJobs", status.failedJobs())
                .field("droppedJobs", status.droppedJobs())
                .field("refreshedAncestors", status.refreshedAncestors())
                .field("tileCacheHits", status.tileCacheHits())
                .field("tileCacheMisses", status.tileCacheMisses())
                .field("cacheHitRatio", status.cacheHitRatio())
                .field("snapshotTimeMs", status.lastSnapshotMillis())
                .field("metatileRenderMs", status.lastRenderMillis())
                .field("tileWriteLatencyMs", status.lastTileWriteMillis())
                .endObject()
                .toString();
    }

    private Optional<String> queryParameter(URI uri, String key) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return Optional.of(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        if (path.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
