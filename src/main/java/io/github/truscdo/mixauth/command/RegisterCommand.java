package io.github.truscdo.mixauth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.truscdo.mixauth.OfflineAuthService;
import io.github.truscdo.mixauth.OfflineAuthSessionService;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * /register 命令：为待认证玩家设置离线密码。
 */
public final class RegisterCommand {
    private RegisterCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> builder() {
        return Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                .executes(context -> execute(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "password"),
                                        StringArgumentType.getString(context, "confirmPassword")))));
    }

    private static int execute(CommandSourceStack source, String password, String confirmPassword) {
        ServerPlayer player = CommandSupport.getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        if (!CommandSupport.validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = player.getGameProfile().getId();
        OfflineAuthSessionService.PendingOfflineAuth pendingOfflineAuth = OfflineAuthSessionService
                .getPendingAuth(player);

        if (pendingOfflineAuth == null) {
            if (OfflineAuthService.isOfflineRegistered(playerUuid)) {
                source.sendFailure(
                        AuthTranslations.componentForSource(source, "auth.error.account_already_has_offline_password"));
                return 0;
            }
        } else if (pendingOfflineAuth.stage() != OfflineAuthSessionService.OfflineAuthStage.REGISTER) {
            source.sendFailure(
                    AuthTranslations.componentForSource(source, "auth.error.account_already_registered_login"));
            OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage());
            return 0;
        }

        try {
            OfflineAuthService.registerOfflineUser(playerUuid, password);
            if (pendingOfflineAuth != null) {
                OfflineAuthService.recordTrustedOfflineLogin(playerUuid, CommandSupport.resolveRemoteIp(player));
                OfflineAuthSessionService.completeAuthentication(player, "auth.message.register_success_auto_login");
            } else {
                source.sendSuccess(
                        () -> AuthTranslations.componentForSource(source, "auth.command.password_create.success"),
                        false);
            }
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_create.failure",
                    CommandSupport.describeCommandFailure(source, "creating an offline password", runtimeException)));
            return 0;
        }
    }
}
