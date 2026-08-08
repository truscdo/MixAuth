package io.github.truscdo.mixauth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.OfflineAuthService;
import io.github.truscdo.mixauth.OfflineAuthSessionService;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * /login 命令：待认证玩家输入密码登录。失败次数超限时触发临时封禁。
 */
public final class LoginCommand {
    private LoginCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> builder() {
        return Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "password"))));
    }

    private static int execute(CommandSourceStack source, String password) {
        ServerPlayer player = CommandSupport.getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        OfflineAuthSessionService.PendingOfflineAuth pendingOfflineAuth = OfflineAuthSessionService
                .getPendingAuth(player);
        if (pendingOfflineAuth == null) {
            source.sendFailure(
                    AuthTranslations.componentForSource(source, "auth.error.no_registration_or_login_needed"));
            return 0;
        }

        if (pendingOfflineAuth.stage() != OfflineAuthSessionService.OfflineAuthStage.LOGIN) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.not_registered_register"));
            OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage());
            return 0;
        }

        if (password.isBlank()) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.password_blank"));
            return 0;
        }

        UUID playerUuid = player.getGameProfile().getId();
        if (OfflineAuthService.verifyOfflinePassword(playerUuid, password)) {
            OfflineAuthService.clearOfflineLoginBlock(playerUuid);
            OfflineAuthService.recordTrustedOfflineLogin(playerUuid, CommandSupport.resolveRemoteIp(player));
            OfflineAuthSessionService.completeAuthentication(player, "auth.message.login_success");
            return Command.SINGLE_SUCCESS;
        }

        int failedAttempts = pendingOfflineAuth.recordFailedLoginAttempt();
        if (failedAttempts >= AuthServerConfig.maxLoginAttempts()) {
            OfflineAuthService.blockOfflineLogin(playerUuid, AuthServerConfig.tempBlockMillis());
            OfflineAuthSessionService.clearPendingAuth(player);
            String language = AuthTranslations.resolveLanguage(player);
            CommandSupport.disconnectPlayer(
                    player,
                    AuthTranslations.componentForPlayer(
                            player,
                            "auth.error.too_many_password_failures_blocked",
                            OfflineAuthService.formatDuration(language, AuthServerConfig.tempBlockMillis())));
            return 0;
        }

        int remainingAttempts = AuthServerConfig.maxLoginAttempts() - failedAttempts;
        pendingOfflineAuth.scheduleNextPrompt();
        source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.password_incorrect_remaining",
                remainingAttempts));
        OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage());
        return 0;
    }
}
