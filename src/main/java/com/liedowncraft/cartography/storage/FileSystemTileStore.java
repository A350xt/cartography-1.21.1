package com.liedowncraft.cartography.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.render.TileImageCodec;

/**
 * Online writable raster store (technical plan v2.0, sections 7.2 and 7.3).
 *
 * <p>Writes go to a temp file, are fsynced, then renamed over the target. A reader therefore never
 * observes a half-written tile: it sees either the previous bytes or the complete new ones.
 *
 * <p>Paths use signed tile coordinates with an explicit {@code x}/{@code y} prefix
 * ({@code .../8/x-3/y12.png}) so a negative coordinate can never be confused with a path separator
 * or a parent-directory reference.
 */
public final class FileSystemTileStore {
    private final Path root;

    public FileSystemTileStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public Optional<StoredTile> read(String tilesetVersion, TileCoordinate tile, String extension) throws IOException {
        Path path = pathFor(tilesetVersion, tile, extension);
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            return Optional.of(new StoredTile(bytes, TileImageCodec.sniffMimeType(bytes)));
        } catch (java.nio.file.NoSuchFileException concurrentDelete) {
            // An ancestor invalidation can delete the tile between the exists check and the read.
            return Optional.empty();
        }
    }

    public boolean exists(String tilesetVersion, TileCoordinate tile, String extension) {
        return Files.exists(pathFor(tilesetVersion, tile, extension));
    }

    /** Atomic write: temp file, fsync, rename (plan section 7.2). */
    public void write(String tilesetVersion, TileCoordinate tile, byte[] bytes, String extension) throws IOException {
        Path path = pathFor(tilesetVersion, tile, extension);
        Files.createDirectories(path.getParent());
        writeAtomically(path, bytes);
    }

    public void delete(String tilesetVersion, TileCoordinate tile, String extension) throws IOException {
        Files.deleteIfExists(pathFor(tilesetVersion, tile, extension));
    }

    /** Publishes the tileset manifest next to its tiles using the same atomic rename discipline. */
    public void writeMetadata(String tilesetVersion, String json) throws IOException {
        Path path = root.resolve("tiles").resolve(tilesetVersion).resolve("metadata.json");
        Files.createDirectories(path.getParent());
        writeAtomically(path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Path pathFor(String tilesetVersion, TileCoordinate tile, String extension) {
        return root.resolve("tiles")
                .resolve(tilesetVersion)
                .resolve(safePathSegment(tile.dimension()))
                .resolve(Integer.toString(tile.zoom()))
                .resolve("x" + tile.x())
                .resolve("y" + tile.y() + "." + extension);
    }

    private void writeAtomically(Path path, byte[] bytes) throws IOException {
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
        }

        try {
            Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException atomicMoveUnsupported) {
            // Some Windows filesystems reject ATOMIC_MOVE with REPLACE_EXISTING; a plain replace is
            // the best available fallback.
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private String safePathSegment(String dimension) {
        return dimension.replace(':', '_').replace('/', '_').replace('\\', '_');
    }
}
