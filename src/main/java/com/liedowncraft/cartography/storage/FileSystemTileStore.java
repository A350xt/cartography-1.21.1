package com.liedowncraft.cartography.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.render.TileImageCodec;

public final class FileSystemTileStore {
    private final Path root;

    public FileSystemTileStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public Optional<StoredTile> read(String tilesetVersion, TileCoordinate tile) throws IOException {
        Path path = pathFor(tilesetVersion, tile);
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        byte[] bytes = Files.readAllBytes(path);
        return Optional.of(new StoredTile(bytes, TileImageCodec.sniffMimeType(bytes)));
    }

    public void write(String tilesetVersion, TileCoordinate tile, byte[] bytes) throws IOException {
        Path path = pathFor(tilesetVersion, tile);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    public void delete(String tilesetVersion, TileCoordinate tile) throws IOException {
        Files.deleteIfExists(pathFor(tilesetVersion, tile));
    }

    public Path pathFor(String tilesetVersion, TileCoordinate tile) {
        return root.resolve("tiles")
                .resolve(tilesetVersion)
                .resolve(safeDimensionPath(tile.dimension()))
                .resolve(Integer.toString(tile.zoom()))
                .resolve(Integer.toString(tile.x()))
                .resolve(tile.y() + ".webp");
    }

    private String safeDimensionPath(String dimension) {
        return dimension.replace(':', '_').replace('/', '_').replace('\\', '_');
    }
}
