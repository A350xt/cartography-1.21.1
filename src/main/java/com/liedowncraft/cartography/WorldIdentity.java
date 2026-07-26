package com.liedowncraft.cartography;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Resolves a stable world identifier for the tileset version hash (technical plan v2.0, section 7.1).
 *
 * <p>No vanilla value is both unique and stable: the level name collides (every singleplayer save is
 * "New World") and is renameable, the seed is shared by any world generated from it, and 1.21.1
 * stores no world UUID. So the id is generated once and persisted inside the save directory, which
 * means it survives folder renames and travels with the world if it is copied or promoted from
 * singleplayer to a dedicated server.
 */
public final class WorldIdentity {
    private static final String ID_DIRECTORY = "cartography";
    private static final String ID_FILE = "world-id";

    private WorldIdentity() {
    }

    /** Reads the persisted world id, generating and storing one on first run. */
    public static String resolve(MinecraftServer server) {
        Path saveRoot = server.getWorldPath(LevelResource.ROOT).normalize();
        Path idFile = saveRoot.resolve(ID_DIRECTORY).resolve(ID_FILE);

        try {
            if (Files.isRegularFile(idFile)) {
                String stored = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!stored.isEmpty()) {
                    return UUID.fromString(stored).toString();
                }
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            Cartography.LOGGER.warn("Could not read the Cartography world id at {}; regenerating", idFile, unreadable);
        }

        String generated = UUID.randomUUID().toString();
        try {
            Files.createDirectories(idFile.getParent());
            Path tempFile = idFile.resolveSibling(ID_FILE + ".tmp");
            Files.writeString(tempFile, generated, StandardCharsets.UTF_8);
            Files.move(tempFile, idFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException writeFailure) {
            // Falling back to the save folder name keeps the tileset namespace stable for this run,
            // at the cost of changing if the folder is later renamed.
            Cartography.LOGGER.warn("Could not persist the Cartography world id; falling back to the save name", writeFailure);
            Path saveName = saveRoot.getFileName();
            return saveName == null ? "world" : saveName.toString();
        }
        return generated;
    }

    /** Directory Cartography may use for its own state inside the save. */
    public static Path stateDirectory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).normalize().resolve(ID_DIRECTORY);
    }
}
