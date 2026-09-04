package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.cache.AuthStore;
import io.github.truscdo.mixauth.db.DatabaseSupport;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
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
            MinecraftServer server = event.getServer();
            // 启动时经单 worker 直接读异步全量加载全部认证数据到内存缓存；加载门控在 AuthStore 内自处理。
            // 加载失败 = 认证服务完全不可用，停机（fail-closed）。
            AuthStore.loadAllAsync().whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Auth cache failed to load; auth service is unusable, halting server", throwable);
                    server.halt(false);
                } else {
                    LOGGER.info("Auth cache fully loaded from database");
                }
            });
        });
        bus.addListener((ServerStoppingEvent event) -> {
            // 关服排空 write-behind 队列后再释放连接池
            AuthStore.drainAndShutdown();
            DatabaseSupport.dispose();
        });
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
