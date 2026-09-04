package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.command.AuthCommandRegistry;
import io.github.truscdo.mixauth.command.CommandSupport;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import io.github.truscdo.mixauth.login.LoginContext;
import io.github.truscdo.mixauth.login.LoginContexts;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;

public final class AuthServerEvents {
    private static final Logger LOGGER = LogUtil.getLogger();

    private AuthServerEvents() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommandRegistry.registerAll(event.getDispatcher());
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        UUID playerUuid = ProfileCompat.uuid(player.getGameProfile());
        String username = ProfileCompat.name(player.getGameProfile());
        Connection connection = player.connection.getConnection();
        LoginContext context = LoginContexts.take(connection);

        if (context == null) {
            if (player.getServer().usesAuthentication()) {
                return;
            }
            LOGGER.error("Missing MixAuth login context for {} ({}) from {}",
                    username, playerUuid, connection.getRemoteAddress());
            player.connection.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.server_internal_error"));
            return;
        }

        if (context.mode() == null
                || !Objects.equals(context.authenticatedProfileId(), playerUuid)
                || !Objects.equals(context.authenticatedUsername(), username)) {
            LOGGER.warn(
                    "Rejected mismatched MixAuth login context from {}: context={} ({}) player={} ({})",
                    connection.getRemoteAddress(),
                    context.authenticatedUsername(),
                    context.authenticatedProfileId(),
                    username,
                    playerUuid);
            player.connection.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.server_internal_error"));
            return;
        }

        OnlineAuthService.LoginMode loginMode = context.mode();

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
