package io.github.truscdo.mixauth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
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

        // BCrypt 校验移到后台有界执行器：主线程只提交任务，结果回到主线程再改状态。
        UUID playerUuid = player.getGameProfile().getId();
        OfflineAuthService.verifyOfflinePasswordAsync(playerUuid, password)
                .whenComplete((verified, throwable) -> CommandSupport.executeOnServerThread(source,
                        () -> completeLogin(source, player, playerUuid, pendingOfflineAuth, verified, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 登录结果回主线程处理（异步校验完成后由 server.execute 调度到主线程）。
     * 异步期间玩家可能已登出/已认证，先校验 pending 仍是同一实例，避免操作已失效状态。
     */
    private static void completeLogin(CommandSourceStack source, ServerPlayer player, UUID playerUuid,
            OfflineAuthSessionService.PendingOfflineAuth pendingOfflineAuth, Boolean verified, Throwable throwable) {
        if (OfflineAuthSessionService.getPendingAuth(player) != pendingOfflineAuth) {
            return;
        }
        if (throwable != null) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.server_busy"));
            return;
        }
        if (Boolean.TRUE.equals(verified)) {
            OfflineAuthService.recordTrustedOfflineLogin(playerUuid, CommandSupport.resolveRemoteIp(player));
            OfflineAuthSessionService.completeAuthentication(player, "auth.message.login_success");
            return;
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
            return;
        }

        int remainingAttempts = AuthServerConfig.maxLoginAttempts() - failedAttempts;
        pendingOfflineAuth.scheduleNextPrompt();
        source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.password_incorrect_remaining",
                remainingAttempts));
        OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage());
    }
}
