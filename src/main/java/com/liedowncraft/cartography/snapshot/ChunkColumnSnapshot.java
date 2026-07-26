package com.liedowncraft.cartography.snapshot;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Immutable copy of the block data a renderer needs from one chunk.
 *
 * <p>Reading a live {@code LevelChunk} from a worker thread is not merely racy: the paletted
 * container does not synchronize reads, so a concurrent palette resize on the server thread can make
 * a lookup miss and crash the server. Copying the palette containers on the server thread and
 * sampling the copy is how map mods avoid this.
 *
 * <p>The copy is taken on the server thread and is then owned entirely by the render worker.
 */
public final class ChunkColumnSnapshot {
    private final int minBuildHeight;
    private final int sectionCount;
    /** One paletted container per section, or null where the section is empty. */
    private final PalettedContainer<BlockState>[] sections;
    private final int[] surfaceHeights;

    private ChunkColumnSnapshot(
            int minBuildHeight,
            int sectionCount,
            PalettedContainer<BlockState>[] sections,
            int[] surfaceHeights) {
        this.minBuildHeight = minBuildHeight;
        this.sectionCount = sectionCount;
        this.sections = sections;
        this.surfaceHeights = surfaceHeights;
    }

    /** Copies a chunk. Must be called on the server thread. */
    @SuppressWarnings("unchecked")
    public static ChunkColumnSnapshot capture(ChunkAccess chunk) {
        LevelChunkSection[] liveSections = chunk.getSections();
        PalettedContainer<BlockState>[] copiedSections = new PalettedContainer[liveSections.length];
        for (int index = 0; index < liveSections.length; index++) {
            LevelChunkSection section = liveSections[index];
            // Skipping empty sections avoids copying the large majority of a typical chunk.
            copiedSections[index] = section == null || section.hasOnlyAir() ? null : section.getStates().copy();
        }

        int[] surfaceHeights = new int[256];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                surfaceHeights[localX + localZ * 16] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
            }
        }

        return new ChunkColumnSnapshot(chunk.getMinBuildHeight(), liveSections.length, copiedSections, surfaceHeights);
    }

    public int minBuildHeight() {
        return minBuildHeight;
    }

    /** Highest non-air block Y, using the same heightmap vanilla's map item reads. */
    public int surfaceHeight(int localX, int localZ) {
        return surfaceHeights[(localX & 15) + (localZ & 15) * 16];
    }

    public BlockState blockState(int localX, int worldY, int localZ) {
        int sectionIndex = SectionPos.blockToSectionCoord(worldY - minBuildHeight);
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            return Blocks.AIR.defaultBlockState();
        }

        PalettedContainer<BlockState> section = sections[sectionIndex];
        if (section == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return section.get(localX & 15, worldY & 15, localZ & 15);
    }

    /** Indexes captured chunks by chunk coordinate for one metatile render. */
    public static final class Region {
        private final Map<Long, ChunkColumnSnapshot> chunks = new HashMap<>();

        public void put(int chunkX, int chunkZ, ChunkColumnSnapshot snapshot) {
            chunks.put(key(chunkX, chunkZ), snapshot);
        }

        /** @return the chunk covering a block position, or null when it was not captured. */
        public ChunkColumnSnapshot at(int blockX, int blockZ) {
            return chunks.get(key(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ)));
        }

        public int size() {
            return chunks.size();
        }

        private static long key(int chunkX, int chunkZ) {
            return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        }
    }
}
