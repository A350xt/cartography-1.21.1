package com.liedowncraft.cartography.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.liedowncraft.cartography.Cartography;
import com.liedowncraft.cartography.bootstrap.CartographyRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Observes every server-side block change (technical plan v2.0, section 6.2).
 *
 * <p>{@code LevelChunk#setBlockState} is the one chokepoint every write funnels through: pistons,
 * fluid flow, explosions, mob griefing, {@code /setblock}, {@code /fill} and bulk world editors all
 * reach it via {@code Level#setBlock}. The NeoForge block events cover only player break and place,
 * and the update-notification hooks are gated behind block-update flags, which is what leaves holes
 * in the map.
 *
 * <p>Worldgen writes to a proto chunk instead and so never arrives here; freshly generated terrain is
 * picked up from {@code ChunkEvent.Load} instead.
 *
 * <p>This is one of the hottest methods in the game, so the handler does nothing but a debounced set
 * insert.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSetBlockStateMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void cartography$onSetBlockState(
            BlockPos pos,
            BlockState state,
            boolean isMoving,
            CallbackInfoReturnable<BlockState> callback) {
        // A null return means nothing actually changed; marking dirty here would thrash the queue.
        BlockState previousState = callback.getReturnValue();
        if (previousState == null || previousState == state) {
            return;
        }

        CartographyRuntime runtime = Cartography.runtime();
        if (runtime == null) {
            return;
        }

        LevelChunk chunk = (LevelChunk) (Object) this;
        Level level = chunk.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // Blockstate churn that cannot change a pixel (redstone dust power, waterlogging toggles)
        // would otherwise re-render tiles constantly.
        if (previousState.getBlock() == state.getBlock()) {
            return;
        }

        runtime.markDirtyChunk(
                serverLevel.dimension().location().toString(),
                chunk.getPos().x,
                chunk.getPos().z);
    }
}
