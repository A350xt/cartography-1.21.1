package com.liedowncraft.cartography;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.snapshot.MainThreadWorldSnapshotProvider;
import com.liedowncraft.cartography.web.PlayerMarker;
import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TimeUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(Cartography.MODID)
public final class Cartography {
    public static final String MODID = "cartography";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Dirty chunks are drained and markers refreshed on this cadence rather than every tick. */
    private static final int PUBLISH_INTERVAL_TICKS = 20;

    private static volatile CartographyRuntime activeRuntime;

    public Cartography(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    /** Exposed so the block-change mixin can reach the runtime without a static import cycle. */
    public static CartographyRuntime runtime() {
        return activeRuntime;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (activeRuntime != null) {
            return;
        }

        MinecraftServer server = event.getServer();
        try {
            CartographySettings settings = Config.settings();

            // ServerStartingEvent also fires for the integrated server, so a singleplayer world would
            // otherwise expose the map on whatever host is configured. Force loopback there.
            if (!server.isDedicatedServer() && !"127.0.0.1".equals(settings.web().bindHost())) {
                LOGGER.info("Integrated server detected; binding the Cartography map to loopback only");
                settings = new CartographySettings(
                        new CartographySettings.WebSettings(
                                settings.web().enabled(),
                                "127.0.0.1",
                                settings.web().port(),
                                settings.web().pendingTileRetryMs()),
                        settings.renderer(),
                        settings.scheduler(),
                        settings.markers(),
                        settings.tileRoot());
            }

            // Keep tiles inside the save directory so each world gets its own cache.
            Path tileRoot = WorldIdentity.stateDirectory(server).resolve(settings.tileRoot());
            settings = new CartographySettings(
                    settings.web(), settings.renderer(), settings.scheduler(), settings.markers(), tileRoot);

            CartographyRuntime runtime = new CartographyRuntime(
                    settings,
                    WorldIdentity.resolve(server),
                    new MainThreadWorldSnapshotProvider(server));
            runtime.start();
            activeRuntime = runtime;
            LOGGER.info("Cartography map server started on {}", runtime.baseUri());
        } catch (Exception exception) {
            LOGGER.error("Failed to start the Cartography runtime", exception);
            activeRuntime = null;
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CartographyRuntime runtime = activeRuntime;
        if (runtime == null) {
            return;
        }

        activeRuntime = null;
        runtime.close();
    }

    /**
     * Publishes TPS, drains debounced dirty chunks, and refreshes markers.
     *
     * <p>Runs on Post so the tick-time tally for this tick has already been folded in, and skips its
     * work when the server has no spare time, so map upkeep never competes with the game.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        CartographyRuntime runtime = activeRuntime;
        if (runtime == null) {
            return;
        }

        MinecraftServer server = event.getServer();
        runtime.setCurrentTps(currentTps(server));

        if (server.getTickCount() % PUBLISH_INTERVAL_TICKS != 0) {
            return;
        }

        if (!event.hasTime()) {
            // The server is already behind this tick; let it catch up.
            return;
        }

        runtime.drainDirtyChunks();
        publishMarkers(server, runtime);
    }

    /**
     * Marks freshly generated chunks dirty. Worldgen writes to a proto chunk, so it never reaches the
     * block-change mixin and has to be picked up here.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        CartographyRuntime runtime = activeRuntime;
        if (runtime == null || !event.isNewChunk()) {
            return;
        }

        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }

        // The chunk may not be promoted to FULL yet, so only record the position; touching the level
        // here risks a chunk-loading deadlock.
        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        runtime.markDirtyChunk(serverLevel.dimension().location().toString(), pos.x, pos.z);
    }

    /**
     * Current TPS, capped at the configured tick rate.
     *
     * <p>The cap matters: average tick time measures only work, not the idle gap between ticks, so an
     * idle server would otherwise report several hundred "TPS".
     */
    private static double currentTps(MinecraftServer server) {
        double targetTps = server.tickRateManager().tickrate();
        long averageNanos = server.getAverageTickTimeNanos() + 1L;
        double measured = (double) TimeUtil.NANOSECONDS_PER_SECOND / averageNanos;
        return Math.min(targetTps, measured);
    }

    /** Snapshots player positions on the server thread; the privacy policy filters them on read. */
    private static void publishMarkers(MinecraftServer server, CartographyRuntime runtime) {
        if (!runtime.settings().markers().enabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        java.util.Set<String> onlineUuids = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Spectators are the closest vanilla analogue to a hidden staff member.
            if (player.isSpectator() || player.isInvisible()) {
                continue;
            }

            String uuid = player.getUUID().toString();
            onlineUuids.add(uuid);
            runtime.upsertMarker(new PlayerMarker(
                    uuid,
                    player.getGameProfile().getName(),
                    player.serverLevel().dimension().location().toString(),
                    player.getX(),
                    player.getZ(),
                    now));
        }

        runtime.retainMarkers(onlineUuids);
    }
}
