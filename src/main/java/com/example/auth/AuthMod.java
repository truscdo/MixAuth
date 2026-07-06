package com.example.auth;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

@Mod(AuthMod.MODID)
public final class AuthMod {
    public static final String MODID = "auth";

    private static final Logger LOGGER = LogUtil.getLogger();

    public AuthMod(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, AuthServerConfig.SPEC, "auth-server.toml");

        LOGGER.info("Loaded mod {}", MODID);
        LOGGER.info("Auth validation handshake interception enabled for offline mode servers");

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(AuthServerEvents::onRegisterCommands);
        bus.addListener(AuthServerEvents::onPlayerLoggedIn);
        OfflineAuthSessionService.registerEventHandlers(bus);
        bus.addListener((ServerAboutToStartEvent event) -> PasswordBlacklistLoader.init());
    }
}