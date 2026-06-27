package com.liedowncraft.cartography.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class CartographyHttpServer implements AutoCloseable {
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
        server.createContext("/manifest.json", exchange -> writeJson(exchange, manifestJson(runtime.manifest())));
        server.createContext("/healthz", exchange -> writeJson(exchange, healthJson(runtime.health())));
        server.createContext("/markers", this::handleMarkers);
        server.createContext("/tiles", this::handleTiles);
        server.createContext("/", this::handleStaticAsset);
    }

    private void handleMarkers(HttpExchange exchange) throws IOException {
        String dimension = queryParameter(exchange.getRequestURI(), "dimension");
        writeJson(exchange, markerJson(runtime.markers(dimension)));
    }

    private void handleTiles(HttpExchange exchange) throws IOException {
        String[] segments = exchange.getRequestURI().getPath().split("/");
        if (segments.length != 7) {
            writeBytes(exchange, 404, "text/plain", "tile path not found".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }

        String tilesetVersion = segments[2];
        String dimension = segments[3];
        int zoom = Integer.parseInt(segments[4]);
        int x = Integer.parseInt(segments[5]);
        int y = Integer.parseInt(segments[6].replace(".webp", ""));

        TileResponse response = runtime.tileResponse(tilesetVersion, new TileCoordinate(dimension, zoom, x, y));
        writeBytes(exchange, response.statusCode(), response.mimeType(), response.body(), response.headers());
    }

    private void handleStaticAsset(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }

        String resourcePath = "web" + path;
        try (InputStream input = runtime.classLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                if ("/index.html".equals(path)) {
                    byte[] fallback = """
                            <!doctype html>
                            <html lang="en">
                              <head><meta charset="utf-8"><title>Cartography</title></head>
                              <body><div id="app">Cartography frontend not built yet.</div></body>
                            </html>
                            """.getBytes(StandardCharsets.UTF_8);
                    writeBytes(exchange, 200, "text/html; charset=utf-8", fallback, Map.of());
                    return;
                }

                writeBytes(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), Map.of());
                return;
            }

            writeBytes(exchange, 200, contentType(path), input.readAllBytes(), Map.of());
        }
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        writeBytes(exchange, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private void writeBytes(HttpExchange exchange, int status, String contentType, byte[] body, Map<String, String> extraHeaders) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        extraHeaders.forEach(headers::set);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private String manifestJson(MapManifest manifest) {
        return """
                {
                  "tileSize": %d,
                  "minZoom": %d,
                  "maxZoom": %d,
                  "pixelsPerBlockAtMaxZoom": %d,
                  "dimensions": [%s],
                  "defaultDimension": "%s",
                  "tilesetVersion": "%s",
                  "tileUrlTemplate": "%s",
                  "markerMode": "%s",
                  "pendingTileRetryMs": %d
                }
                """.formatted(
                manifest.tileSize(),
                manifest.minZoom(),
                manifest.maxZoom(),
                manifest.pixelsPerBlockAtMaxZoom(),
                quoteList(manifest.dimensions()),
                manifest.defaultDimension(),
                manifest.tilesetVersion(),
                manifest.tileUrlTemplate(),
                manifest.markerMode(),
                manifest.pendingTileRetryMs());
    }

    private String markerJson(List<PlayerMarker> markers) {
        String payload = markers.stream()
                .map(marker -> """
                        {"uuid":"%s","name":"%s","dimension":"%s","x":%s,"z":%s,"updatedAt":%d}
                        """.formatted(marker.uuid(), marker.name(), marker.dimension(), marker.x(), marker.z(), marker.updatedAt()))
                .collect(Collectors.joining(","));
        return "{\"players\":[" + payload + "]}";
    }

    private String healthJson(HealthStatus status) {
        return """
                {
                  "alive": %s,
                  "queueDepth": %d,
                  "schedulerPaused": %s,
                  "tilesetVersion": "%s"
                }
                """.formatted(status.alive(), status.queueDepth(), status.schedulerPaused(), status.tilesetVersion());
    }

    private String quoteList(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(","));
    }

    private String queryParameter(URI uri, String key) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return runtime.manifest().defaultDimension();
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return parts[1];
            }
        }
        return runtime.manifest().defaultDimension();
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
