package com.liedowncraft.cartography;

import org.slf4j.Logger;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Cartography.MODID)
public final class Cartography {
    public static final String MODID = "cartography";
    public static final Logger LOGGER = LogUtils.getLogger();

    private volatile CartographyRuntime runtime;

    public Cartography(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (runtime != null) {
            return;
        }

        try {
            runtime = new CartographyRuntime(Config.settings(), event.getServer());
            runtime.start();
            LOGGER.info("Cartography embedded web server started on {}", runtime.baseUri());
        } catch (Exception exception) {
            LOGGER.error("Failed to start Cartography runtime", exception);
            runtime = null;
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (runtime == null) {
            return;
        }

        runtime.close();
        runtime = null;
    }
}
