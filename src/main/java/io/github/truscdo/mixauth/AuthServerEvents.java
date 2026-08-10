package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.command.AuthCommandRegistry;
import io.github.truscdo.mixauth.command.CommandSupport;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.UUID;

public final class AuthServerEvents {
    private AuthServerEvents() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommandRegistry.registerAll(event.getDispatcher());
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        UUID playerUuid = player.getGameProfile().getId();

        OnlineAuthService.LoginMode loginMode = OnlineAuthService.consumeLoginMode(playerUuid);
        if (loginMode == null) {
            return;
        }

        String language = AuthTranslations.resolveLanguage(player);
        player.sendSystemMessage(AuthTranslations.componentForPlayer(
                player,
                "auth.message.current_login_mode",
                AuthTranslations.textForLanguage(language, switch (loginMode) {
                    case ONLINE -> "auth.login_mode.online";
                    case OFFLINE -> "auth.login_mode.offline";
                })));
        if (loginMode != OnlineAuthService.LoginMode.OFFLINE) {
            return;
        }

        boolean offlineRegistered = OfflineAuthService.isOfflineRegistered(playerUuid);
        String remoteIp = CommandSupport.resolveRemoteIp(player);
        if (offlineRegistered && OfflineAuthService.canBypassOfflineLogin(playerUuid, remoteIp)) {
            player.sendSystemMessage(AuthTranslations.componentForPlayer(
                    player,
                    "auth.message.trusted_login_bypass",
                    OfflineAuthService.describeTrustedLoginWindow(language)));
            return;
        }

        OfflineAuthSessionService.OfflineAuthStage stage = offlineRegistered
                ? OfflineAuthSessionService.OfflineAuthStage.LOGIN
                : OfflineAuthSessionService.OfflineAuthStage.REGISTER;
        OfflineAuthSessionService.beginPendingAuth(player, stage);
    }
}