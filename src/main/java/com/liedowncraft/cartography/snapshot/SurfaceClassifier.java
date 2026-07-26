package com.liedowncraft.cartography.snapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Classifies a block into a {@link SurfaceKind} (technical plan v2.0, section 5.3).
 *
 * <p>Classification is by geometry and occlusion rather than by block id or tag. That generalizes to
 * modded blocks, because {@code .noOcclusion()} plus an accurate shape is how transparency is
 * authored, and it avoids the traps in the obvious tags: {@code IMPERMEABLE} excludes ice and glass
 * panes, {@code SNOW} includes the fully opaque snow block, and {@code WOOL_CARPETS} excludes moss
 * carpet.
 *
 * <p>Crucially this does not classify by map colour. Plain {@code minecraft:glass} is registered
 * with no map colour, so vanilla's "walk down until the block has a colour" loop falls straight
 * through a glass roof. Terminating on classification instead is what keeps glass buildings visible.
 */
public final class SurfaceClassifier {
    /** Carpet is 1/16 tall and a snow layer is 2n/16; this keeps layers 1-2 as overlay. */
    private static final double THIN_OVERLAY_MAX_HEIGHT = 0.25;
    private static final double EPSILON = 1.0E-6;

    private SurfaceClassifier() {
    }

    /**
     * @return the kind of surface this block presents from above, or null when the block should be
     *     skipped and the downward walk continued (air and other invisible blocks).
     */
    public static SurfaceKind classify(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir()) {
            return null;
        }

        if (!state.getFluidState().isEmpty()) {
            return SurfaceKind.FLUID_SURFACE;
        }

        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            // Torches, flowers, rails, buttons: visible detail with no surface of their own.
            return SurfaceKind.DECORATION_VISUAL;
        }

        double top = shape.max(Direction.Axis.Y);
        double bottom = shape.min(Direction.Axis.Y);

        // Sits on the floor and is barely any height: carpets, moss carpet, shallow snow.
        if (bottom <= EPSILON && top <= THIN_OVERLAY_MAX_HEIGHT) {
            return SurfaceKind.THIN_OVERLAY;
        }

        // A full cube that actually occludes. Checked before the transparency test so packed ice,
        // blue ice and snow blocks are treated as the solid terrain they visually are.
        if (state.isSolidRender(level, pos)) {
            return state.is(BlockTags.LEAVES) ? SurfaceKind.FOLIAGE_SURFACE : SurfaceKind.OPAQUE_SURFACE;
        }

        // Leaves are noOcclusion with a full shape, so they must be caught before the glass test.
        if (state.is(BlockTags.LEAVES)) {
            return SurfaceKind.FOLIAGE_SURFACE;
        }

        // Full-height but non-occluding: glass, stained and tinted glass, ice, frosted ice, panes,
        // and any modded block authored the same way.
        if (top >= 1.0 - EPSILON && !state.canOcclude()) {
            return SurfaceKind.TRANSPARENT_STRUCTURE;
        }

        return SurfaceKind.UNKNOWN_FALLBACK;
    }

    /** Whether a kind terminates the downward search for a surface. */
    public static boolean terminatesWalk(SurfaceKind kind) {
        return switch (kind) {
            case OPAQUE_SURFACE, FOLIAGE_SURFACE, TRANSPARENT_STRUCTURE, FLUID_SURFACE, UNKNOWN_FALLBACK -> true;
            // Overlays and decoration are composited onto whatever lies beneath them.
            case THIN_OVERLAY, DECORATION_VISUAL -> false;
        };
    }
}
