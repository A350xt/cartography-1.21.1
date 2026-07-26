package com.liedowncraft.cartography.snapshot;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileBounds;
import com.liedowncraft.cartography.core.TileGrid;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.MapColor;

/**
 * Samples world surface columns for a metatile (technical plan v2.0, sections 3.1 and 5.1).
 *
 * <p>Split deliberately in two: the server thread only copies chunk block data, and all per-pixel
 * work runs on the calling render worker. A padded 4x4 metatile is on the order of a million column
 * scans, which must never happen inside a tick.
 *
 * <p>The copy is required for correctness as well as for TPS. Paletted containers do not synchronize
 * reads, so sampling a live chunk from a worker can miss a palette entry mid-resize and crash the
 * server.
 */
public final class MainThreadWorldSnapshotProvider implements WorldSnapshotProvider {
    /** Bounded so a worker cannot hang if the server stops with the capture still queued. */
    private static final long SERVER_THREAD_TIMEOUT_MS = 5_000L;

    private final MinecraftServer server;

    public MainThreadWorldSnapshotProvider(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public SampledMapBuffer capture(MetatileJob job, RendererProfile profile) throws IOException {
        TileGrid grid = profile.tileGrid();
        int blocksPerPixel = grid.blocksPerPixel(job.zoom());
        // At least one pixel of padding, so the first published row always has a real neighbour to
        // shade against. More when a pixel covers fewer blocks than the configured padding.
        int paddingPixels = Math.max(1, ceilDiv(profile.paddingBlocks(), blocksPerPixel));
        int publishedPixels = profile.tileSize() * job.tileCount();
        int totalPixels = publishedPixels + paddingPixels * 2;

        // Derive block extents from the pixel lattice rather than from a rounded ratio: at high zoom
        // a pixel covers less than a block, and multiplying by a rounded value would oversample.
        TileBounds published = job.blockBounds(grid);
        int paddingBlocks = blocksBetweenPixels(grid, job.zoom(), paddingPixels);
        int originBlockX = published.minBlockX() - paddingBlocks;
        int originBlockZ = published.minBlockZ() - paddingBlocks;
        int sampledBlocks = blocksBetweenPixels(grid, job.zoom(), totalPixels);
        TileBounds sampled = new TileBounds(
                originBlockX,
                originBlockZ,
                originBlockX + sampledBlocks,
                originBlockZ + sampledBlocks);

        LevelSnapshot levelSnapshot = onServerThread(job, sampled);

        SampledMapBuffer.Builder buffer =
                SampledMapBuffer.builder(totalPixels, totalPixels, paddingPixels, blocksPerPixel);

        if (levelSnapshot.hasCeiling()) {
            fillCeilingFallback(buffer, grid, job.zoom(), originBlockX, originBlockZ);
            return buffer.build();
        }

        return sampleOffThread(levelSnapshot, buffer, grid, job.zoom(), originBlockX, originBlockZ, totalPixels);
    }

    /**
     * Blocks spanned by {@code pixelCount} pixels at {@code zoom}.
     *
     * <p>Derived from the pixel lattice, so it stays correct at sub-block resolution: at two pixels
     * per block, 260 pixels span 130 blocks, not 260. Assuming a block per pixel there would make
     * every job demand twice the chunks it needs, and the extra ones are usually not loaded.
     */
    private static int blocksBetweenPixels(TileGrid grid, int zoom, int pixelCount) {
        return Math.max(1, grid.firstBlockOfPixel(zoom, pixelCount));
    }

    /** Copies the chunks the metatile needs. Runs on the server thread and does no per-pixel work. */
    private LevelSnapshot onServerThread(MetatileJob job, TileBounds sampled) throws IOException {
        // After shutdown, submit() runs the supplier inline on the caller instead of scheduling it,
        // which would read chunks off-thread with no error raised. Refuse up front.
        if (server.isStopped() || !server.isRunning()) {
            throw new SnapshotUnavailableException("Server is shutting down");
        }

        try {
            return server.submit(() -> {
                try {
                    return captureChunks(job, sampled);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }).get(SERVER_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            throw new SnapshotUnavailableException("Server rejected the snapshot task; it is shutting down");
        } catch (TimeoutException timeout) {
            // Queued tasks are not drained on shutdown, so an unbounded wait could hang forever.
            throw new SnapshotUnavailableException("Timed out waiting for the server thread");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SnapshotUnavailableException("Interrupted while waiting for the server thread");
        } catch (ExecutionException | CompletionException exception) {
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

    private LevelSnapshot captureChunks(MetatileJob job, TileBounds sampled) throws IOException {
        ResourceLocation dimensionId = ResourceLocation.tryParse(job.dimension());
        if (dimensionId == null) {
            throw new SnapshotUnavailableException("Malformed dimension id " + job.dimension());
        }

        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) {
            throw new SnapshotUnavailableException("Dimension " + job.dimension() + " is not loaded");
        }

        boolean hasCeiling = level.dimensionType().hasCeiling();
        if (hasCeiling) {
            return new LevelSnapshot(level, true, new ChunkColumnSnapshot.Region());
        }

        int minSectionX = SectionPos.blockToSectionCoord(sampled.minBlockX());
        int maxSectionX = SectionPos.blockToSectionCoord(sampled.maxBlockXExclusive() - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(sampled.minBlockZ());
        int maxSectionZ = SectionPos.blockToSectionCoord(sampled.maxBlockZExclusive() - 1);

        ChunkColumnSnapshot.Region region = new ChunkColumnSnapshot.Region();
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                // requireChunk=false so a render job never triggers world generation.
                ChunkAccess chunk = level.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    throw new SnapshotUnavailableException(
                            "Chunk " + sectionX + "," + sectionZ + " is not loaded for sampling");
                }

                // A loaded full chunk can be handed back wrapped; unwrap so section data is the real
                // chunk's rather than the imposter's.
                if (chunk instanceof ImposterProtoChunk imposter) {
                    chunk = imposter.getWrapped();
                }

                region.put(sectionX, sectionZ, ChunkColumnSnapshot.capture(chunk));
            }
        }
        return new LevelSnapshot(level, false, region);
    }

    /** Per-pixel sampling against the copied chunk data. Runs on a render worker. */
    private SampledMapBuffer sampleOffThread(
            LevelSnapshot levelSnapshot,
            SampledMapBuffer.Builder buffer,
            TileGrid grid,
            int zoom,
            int originBlockX,
            int originBlockZ,
            int totalPixels) throws IOException {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int[] counts = new int[64];
        int[] order = new int[64];

        for (int pixelX = 0; pixelX < totalPixels; pixelX++) {
            // Block extents come from the pixel lattice, so this is exact whether one pixel covers
            // many blocks or several pixels share a single block.
            int baseBlockX = originBlockX + grid.firstBlockOfPixel(zoom, pixelX);
            int spanX = grid.blockSpanOfPixel(zoom, pixelX);
            for (int pixelY = 0; pixelY < totalPixels; pixelY++) {
                int baseBlockZ = originBlockZ + grid.firstBlockOfPixel(zoom, pixelY);
                int spanZ = grid.blockSpanOfPixel(zoom, pixelY);
                int sampleArea = spanX * spanZ;
                Arrays.fill(counts, 0);
                int seenCount = 0;
                double averageHeight = 0.0;
                // Vanilla holds this as an int and truncates on the divide.
                int fluidDepth = 0;
                int overlayColor = 0;
                SurfaceKind dominantKind = SurfaceKind.OPAQUE_SURFACE;

                for (int offsetX = 0; offsetX < spanX; offsetX++) {
                    for (int offsetZ = 0; offsetZ < spanZ; offsetZ++) {
                        int worldX = baseBlockX + offsetX;
                        int worldZ = baseBlockZ + offsetZ;
                        ChunkColumnSnapshot chunk = levelSnapshot.region().at(worldX, worldZ);
                        if (chunk == null) {
                            throw new SnapshotUnavailableException(
                                    "Chunk data missing for block " + worldX + "," + worldZ);
                        }

                        ColumnSample sample = sampleColumn(levelSnapshot, chunk, cursor, worldX, worldZ);

                        int colorId = sample.mapColorId();
                        if (counts[colorId]++ == 0) {
                            order[seenCount++] = colorId;
                        }
                        averageHeight += (double) sample.surfaceY() / sampleArea;
                        fluidDepth += sample.fluidDepth();
                        if (sample.overlayColor() != 0) {
                            overlayColor = sample.overlayColor();
                        }
                        dominantKind = sample.kind();
                    }
                }

                buffer.setAt(
                        pixelX,
                        pixelY,
                        (byte) dominantColorId(counts, order, seenCount),
                        (float) averageHeight,
                        fluidDepth / sampleArea,
                        dominantKind,
                        overlayColor);
            }
        }

        return buffer.build();
    }

    /**
     * Walks one column down to the first block that presents a surface.
     *
     * <p>Unlike vanilla this stops on classification rather than on the block having a map colour.
     * Plain glass has no map colour, so a colour-driven walk falls straight through a glass roof and
     * renders the floor below; stopping on classification is what keeps glass buildings visible.
     */
    private ColumnSample sampleColumn(
            LevelSnapshot levelSnapshot,
            ChunkColumnSnapshot chunk,
            BlockPos.MutableBlockPos cursor,
            int worldX,
            int worldZ) {
        int minBuildHeight = chunk.minBuildHeight();
        int surfaceY = chunk.surfaceHeight(worldX, worldZ) + 1;
        if (surfaceY <= minBuildHeight + 1) {
            return new ColumnSample(
                    Byte.toUnsignedInt(VanillaMapPalette.STONE), minBuildHeight, 0, SurfaceKind.OPAQUE_SURFACE, 0);
        }

        Level level = levelSnapshot.level();
        int currentY = surfaceY;
        int overlayColor = 0;
        SurfaceKind kind = SurfaceKind.UNKNOWN_FALLBACK;
        BlockState state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

        while (currentY > minBuildHeight) {
            currentY--;
            cursor.set(worldX, currentY, worldZ);
            state = chunk.blockState(worldX, currentY, worldZ);
            SurfaceKind candidate = SurfaceClassifier.classify(state, level, cursor);
            if (candidate == null || candidate == SurfaceKind.DECORATION_VISUAL) {
                continue;
            }

            if (candidate == SurfaceKind.THIN_OVERLAY) {
                // Remember the covering and keep descending to the base it rests on.
                if (overlayColor == 0) {
                    overlayColor = translucent(state.getMapColor(level, cursor));
                }
                continue;
            }

            kind = candidate;
            break;
        }

        int fluidDepth = 0;
        if (kind == SurfaceKind.FLUID_SURFACE) {
            int probeY = currentY;
            while (probeY > minBuildHeight) {
                probeY--;
                if (chunk.blockState(worldX, probeY, worldZ).getFluidState().isEmpty()) {
                    break;
                }
                fluidDepth++;
            }
            cursor.set(worldX, currentY, worldZ);
            state = correctStateForFluidBlock(level, state, cursor);
        } else if (kind == SurfaceKind.TRANSPARENT_STRUCTURE) {
            // Blend the structure over what it covers, so a greenhouse reads as glass over floor
            // rather than as an opaque slab.
            overlayColor = translucent(state.getMapColor(level, cursor));
            int probeY = currentY;
            BlockPos.MutableBlockPos beneath = new BlockPos.MutableBlockPos();
            while (probeY > minBuildHeight) {
                probeY--;
                beneath.set(worldX, probeY, worldZ);
                BlockState below = chunk.blockState(worldX, probeY, worldZ);
                SurfaceKind belowKind = SurfaceClassifier.classify(below, level, beneath);
                if (belowKind == null
                        || belowKind == SurfaceKind.TRANSPARENT_STRUCTURE
                        || belowKind == SurfaceKind.DECORATION_VISUAL) {
                    continue;
                }

                MapColor belowColor = below.getMapColor(level, beneath);
                if (belowColor != MapColor.NONE) {
                    return new ColumnSample(belowColor.id, currentY, 0, belowKind, overlayColor);
                }
                break;
            }
        }

        cursor.set(worldX, currentY, worldZ);
        return new ColumnSample(state.getMapColor(level, cursor).id, currentY, fluidDepth, kind, overlayColor);
    }

    /** Half-opacity ARGB used when compositing an overlay or a transparent structure. */
    private int translucent(MapColor mapColor) {
        if (mapColor == MapColor.NONE) {
            return 0;
        }
        return 0x80000000 | (mapColor.col & 0x00FFFFFF);
    }

    private void fillCeilingFallback(
            SampledMapBuffer.Builder buffer, TileGrid grid, int zoom, int minBlockX, int minBlockZ) {
        // Mirrors vanilla's nether hash pattern; a true top-down view of a roofed dimension would only
        // ever show bedrock.
        for (int pixelX = 0; pixelX < buffer.width(); pixelX++) {
            int baseBlockX = minBlockX + grid.firstBlockOfPixel(zoom, pixelX);
            for (int pixelY = 0; pixelY < buffer.height(); pixelY++) {
                int baseBlockZ = minBlockZ + grid.firstBlockOfPixel(zoom, pixelY);
                int hash = baseBlockX + baseBlockZ * 231871;
                hash = hash * hash * 31287121 + hash * 11;
                byte colorId = (byte) (((hash >> 20) & 1) == 0 ? VanillaMapPalette.DIRT : VanillaMapPalette.STONE);
                buffer.setAt(pixelX, pixelY, colorId, 100.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0);
            }
        }
    }

    private BlockState correctStateForFluidBlock(Level level, BlockState state, BlockPos pos) {
        return !state.getFluidState().isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP)
                ? state.getFluidState().createLegacyBlock()
                : state;
    }

    /** Most frequent colour in the sample, ties broken by first appearance, as vanilla does. */
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

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * Chunk copies plus the level they came from.
     *
     * <p>The level reference is only used for position-dependent block queries such as map colour and
     * shape, which mods may override. Those are read-only and safe off-thread; block data itself comes
     * from the copies.
     */
    private record LevelSnapshot(ServerLevel level, boolean hasCeiling, ChunkColumnSnapshot.Region region) {
    }

    private record ColumnSample(int mapColorId, int surfaceY, int fluidDepth, SurfaceKind kind, int overlayColor) {
    }
}
