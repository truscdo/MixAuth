package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.db.DatabaseSupport;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(AuthMod.MODID)
public final class AuthMod {
    public static final String MODID = "mixauth";

    private static final Logger LOGGER = LogUtil.getLogger();

    public AuthMod(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, AuthServerConfig.SPEC, "mixauth-server.toml");

        LOGGER.info("Loaded mod {}", MODID);

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(AuthServerEvents::onRegisterCommands);
        bus.addListener(AuthServerEvents::onPlayerLoggedIn);
        OfflineAuthSessionService.registerEventHandlers(bus);
        bus.addListener((ServerAboutToStartEvent event) -> {
            PasswordBlacklistLoader.init();
            warnIfOnlineMode(event.getServer());
        });
        bus.addListener((ServerStoppingEvent event) -> DatabaseSupport.dispose());
    }

    /** MixAuth 仅支持离线模式服务器；正版模式下手握拦截完全失效，打印警告提醒管理员。 */
    private static void warnIfOnlineMode(MinecraftServer server) {
        if (server.usesAuthentication()) {
            LOGGER.warn("==================================================================");
            LOGGER.warn("MixAuth does not work on online-mode (premium) servers!");
            LOGGER.warn("The server has online-mode=true, so authentication interception is disabled");
            LOGGER.warn("and this mod will have no effect. Set online-mode=false in server.properties");
            LOGGER.warn("to enable MixAuth.");
            LOGGER.warn("==================================================================");
        }
    }
}