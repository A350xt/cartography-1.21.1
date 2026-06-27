package com.liedowncraft.cartography.snapshot;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileBounds;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.core.TileMath;
import com.liedowncraft.cartography.render.VanillaMapPalette;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

public final class MainThreadWorldSnapshotProvider implements WorldSnapshotProvider {
    private final MinecraftServer server;

    public MainThreadWorldSnapshotProvider(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public SampledMapBuffer capture(MetatileJob job, RendererProfile profile) throws IOException {
        try {
            return server.submit(() -> {
                try {
                    return captureOnServerThread(job, profile);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Failed to capture world snapshot", cause);
        }
    }

    private SampledMapBuffer captureOnServerThread(MetatileJob job, RendererProfile profile) throws IOException {
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(job.dimension()));
        ServerLevel level = server.getLevel(dimensionKey);
        if (level == null) {
            throw new IOException("Unknown dimension " + job.dimension());
        }

        int pixelsPerTile = profile.tileSize();
        int totalPixels = pixelsPerTile * job.tileCount();
        int blocksPerTile = TileMath.blocksPerTile(job.zoom(), profile);
        if (blocksPerTile % pixelsPerTile != 0) {
            throw new IOException("blocksPerTile must divide tileSize for vanilla-style sampling");
        }

        int blocksPerPixel = blocksPerTile / pixelsPerTile;
        TileBounds metatileBounds = TileMath.tileToBlockBounds(new TileCoordinate(job.dimension(), job.zoom(), job.startX(), job.startY()), profile);
        SampledMapBuffer.Builder buffer = SampledMapBuffer.builder(totalPixels, totalPixels, blocksPerPixel);

        if (level.dimensionType().hasCeiling()) {
            fillCeilingFallback(buffer, metatileBounds.minBlockX(), metatileBounds.minBlockZ(), blocksPerPixel);
            return buffer.build();
        }

        Map<Long, LevelChunk> chunks = collectLoadedChunks(level, metatileBounds);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos fluidCursor = new BlockPos.MutableBlockPos();
        int minBuildHeight = level.getMinBuildHeight();
        int sampleArea = blocksPerPixel * blocksPerPixel;
        int[] counts = new int[64];
        int[] order = new int[64];

        for (int pixelX = 0; pixelX < totalPixels; pixelX++) {
            int baseBlockX = metatileBounds.minBlockX() + pixelX * blocksPerPixel;
            for (int pixelY = 0; pixelY < totalPixels; pixelY++) {
                int baseBlockZ = metatileBounds.minBlockZ() + pixelY * blocksPerPixel;
                Arrays.fill(counts, 0);
                int seenCount = 0;
                float averageHeight = 0.0F;
                float fluidDepth = 0.0F;

                for (int offsetX = 0; offsetX < blocksPerPixel; offsetX++) {
                    for (int offsetZ = 0; offsetZ < blocksPerPixel; offsetZ++) {
                        int worldX = baseBlockX + offsetX;
                        int worldZ = baseBlockZ + offsetZ;
                        LevelChunk chunk = chunks.get(chunkKey(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ)));
                        cursor.set(worldX, 0, worldZ);
                        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, worldX & 15, worldZ & 15) + 1;
                        BlockState state;
                        if (surfaceY <= minBuildHeight + 1) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        } else {
                            int currentY = surfaceY;
                            do {
                                cursor.setY(--currentY);
                                state = chunk.getBlockState(cursor);
                            } while (state.getMapColor(level, cursor) == MapColor.NONE && currentY > minBuildHeight);

                            if (currentY > minBuildHeight && !state.getFluidState().isEmpty()) {
                                int fluidY = currentY - 1;
                                fluidCursor.set(cursor);
                                BlockState fluidState;
                                do {
                                    fluidCursor.setY(fluidY--);
                                    fluidState = chunk.getBlockState(fluidCursor);
                                    fluidDepth += 1.0F;
                                } while (fluidY > minBuildHeight && !fluidState.getFluidState().isEmpty());

                                state = correctStateForFluidBlock(level, state, cursor);
                            }
                            surfaceY = currentY;
                        }

                        MapColor mapColor = state.getMapColor(level, cursor);
                        int colorId = mapColor.id;
                        if (counts[colorId]++ == 0) {
                            order[seenCount++] = colorId;
                        }
                        averageHeight += (float)surfaceY / sampleArea;
                    }
                }

                int dominantColorId = dominantColorId(counts, order, seenCount);
                buffer.set(
                        pixelX,
                        pixelY,
                        (byte)dominantColorId,
                        averageHeight,
                        fluidDepth / sampleArea,
                        dominantColorId == Byte.toUnsignedInt(VanillaMapPalette.WATER));
            }
        }

        return buffer.build();
    }

    private void fillCeilingFallback(SampledMapBuffer.Builder buffer, int minBlockX, int minBlockZ, int blocksPerPixel) {
        for (int pixelX = 0; pixelX < buffer.width(); pixelX++) {
            int baseBlockX = minBlockX + pixelX * blocksPerPixel;
            for (int pixelY = 0; pixelY < buffer.height(); pixelY++) {
                int baseBlockZ = minBlockZ + pixelY * blocksPerPixel;
                int hash = baseBlockX + baseBlockZ * 231871;
                hash = hash * hash * 31287121 + hash * 11;
                byte colorId = (byte)(((hash >> 20) & 1) == 0 ? VanillaMapPalette.DIRT : VanillaMapPalette.STONE);
                buffer.set(pixelX, pixelY, colorId, 100.0F, 0.0F, false);
            }
        }
    }

    private BlockState correctStateForFluidBlock(ServerLevel level, BlockState state, BlockPos pos) {
        return !state.getFluidState().isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP)
                ? state.getFluidState().createLegacyBlock()
                : state;
    }

    private Map<Long, LevelChunk> collectLoadedChunks(ServerLevel level, TileBounds metatileBounds) throws SnapshotUnavailableException {
        int minSectionX = SectionPos.blockToSectionCoord(metatileBounds.minBlockX());
        int maxSectionX = SectionPos.blockToSectionCoord(metatileBounds.maxBlockXExclusive() - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(metatileBounds.minBlockZ());
        int maxSectionZ = SectionPos.blockToSectionCoord(metatileBounds.maxBlockZExclusive() - 1);

        Map<Long, LevelChunk> chunks = new HashMap<>();
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                ChunkAccess chunkAccess = level.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
                if (!(chunkAccess instanceof LevelChunk chunk)) {
                    throw new SnapshotUnavailableException("Chunk " + sectionX + "," + sectionZ + " is not loaded for sampling");
                }
                chunks.put(chunkKey(sectionX, sectionZ), chunk);
            }
        }
        return chunks;
    }

    private int dominantColorId(int[] counts, int[] order, int seenCount) {
        int bestColorId = 0;
        int bestCount = -1;
        for (int index = 0; index < seenCount; index++) {
            int colorId = order[index];
            int count = counts[colorId];
            if (count > bestCount) {
                bestCount = count;
                bestColorId = colorId;
            }
        }
        return bestColorId;
    }

    private long chunkKey(int sectionX, int sectionZ) {
        return ((long)sectionX << 32) ^ (sectionZ & 0xFFFFFFFFL);
    }
}
