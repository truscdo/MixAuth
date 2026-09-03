package io.github.truscdo.mixauth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /auth 命令：管理员子命令 setpassword / remove 与玩家自助子命令 changepassword。
 */
public final class AuthCommands {
    private static final SuggestionProvider<CommandSourceStack> KNOWN_PLAYERS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (remaining.isEmpty()) {
            return Suggestions.empty();
        }
        List<KnownPlayerDao.KnownPlayerEntry> entries = KnownPlayerService.findKnownPlayersByPrefix(remaining, 20);
        if (entries == null || entries.isEmpty()) {
            return Suggestions.empty();
        }
        for (var entry : entries) {
            String tooltip = entry.username() + " (" + entry.playerUuid() + ")";
            Component tooltipComponent = Component.literal(tooltip);
            String usernameLower = entry.username().toLowerCase(Locale.ROOT);
            String uuidStr = entry.playerUuid().toString().toLowerCase(Locale.ROOT);
            if (usernameLower.startsWith(remaining)) {
                builder.suggest(entry.username(), tooltipComponent);
            } else if (uuidStr.startsWith(remaining)) {
                builder.suggest(uuidStr, tooltipComponent);
            }
        }
        return builder.buildFuture();
    };

    private AuthCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> builder() {
        return Commands.literal("auth")
                .then(Commands.literal("changepassword")
                        .then(Commands.argument("password", StringArgumentType.word())
                                .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                        .executes(context -> changeOwnOfflinePassword(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "password"),
                                                StringArgumentType.getString(context, "confirmPassword"))))))
                .then(Commands.literal("setpassword")
                        .requires(PermissionCompat::isAdmin)
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.word())
                                        .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                                .executes(context -> setOfflinePassword(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "password"),
                                                        StringArgumentType.getString(context,
                                                                "confirmPassword")))))))
                .then(Commands.literal("remove")
                        .requires(PermissionCompat::isAdmin)
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(KNOWN_PLAYERS)
                                .executes(context -> removePlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "target")))));
    }

    /**
     * /auth changepassword：玩家自助修改自己的离线密码。
     */
    private static int changeOwnOfflinePassword(CommandSourceStack source, String password, String confirmPassword) {
        ServerPlayer player = CommandSupport.getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        if (!CommandSupport.validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = ProfileCompat.uuid(player.getGameProfile());
        if (!OfflineAuthService.isOfflineRegistered(playerUuid)) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.no_offline_password_register"));
            return 0;
        }

        // BCrypt 哈希移到后台有界执行器，落库与清理回主线程执行。
        OfflineAuthService.hashOfflinePasswordAsync(password)
                .whenComplete((hash, throwable) -> CommandSupport.executeOnServerThread(source,
                        () -> completeChangePassword(source, playerUuid, hash, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    private static void completeChangePassword(CommandSourceStack source, UUID playerUuid, String hash,
            Throwable throwable) {
        if (throwable != null) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.server_busy"));
            return;
        }
        try {
            OfflineAuthService.saveOfflinePasswordHash(playerUuid, hash);
            OfflineAuthService.clearTrustedOfflineLogins(playerUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_change.success",
                            OfflineAuthService.describeTrustedLoginWindow(language)),
                    false);
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_change.failure",
                    CommandSupport.describeCommandFailure(source, "changing an offline password", runtimeException)));
        }
    }

    /**
     * /auth setpassword：管理员为指定玩家设置离线密码。
     */
    private static int setOfflinePassword(CommandSourceStack source, String target, String password,
            String confirmPassword) {
        if (!CommandSupport.validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = PlayerTargetResolver.tryParseUuid(target);
        if (playerUuid == null) {
            playerUuid = PlayerTargetResolver.resolvePlayerUuidByUsername(source, target);
        }
        if (playerUuid == null) {
            return 0;
        }
        final UUID resolvedUuid = playerUuid;

        // BCrypt 哈希移到后台有界执行器，落库与清理回主线程执行。
        OfflineAuthService.hashOfflinePasswordAsync(password)
                .whenComplete((hash, throwable) -> CommandSupport.executeOnServerThread(source,
                        () -> completeSetPassword(source, resolvedUuid, hash, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    private static void completeSetPassword(CommandSourceStack source, UUID resolvedUuid, String hash,
            Throwable throwable) {
        if (throwable != null) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.server_busy"));
            return;
        }
        try {
            OfflineAuthService.saveOfflinePasswordHash(resolvedUuid, hash);
            OfflineAuthService.clearTrustedOfflineLogins(resolvedUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_set.success",
                            resolvedUuid,
                            OfflineAuthService.describeTrustedLoginWindow(language)),
                    true);
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_set.failure",
                    CommandSupport.describeCommandFailure(source, "setting an offline password", runtimeException)));
        }
    }

    /**
     * /auth remove：管理员移除玩家的全部认证数据。
     */
    private static int removePlayer(CommandSourceStack source, String target) {
        UUID playerUuid = PlayerTargetResolver.tryParseUuid(target);
        if (playerUuid == null) {
            playerUuid = PlayerTargetResolver.resolvePlayerUuidByUsername(source, target);
        }
        if (playerUuid == null) {
            return 0;
        }
        final UUID resolvedUuid = playerUuid;

        try {
            KnownPlayerService.removeAllPlayerData(resolvedUuid);
            source.sendSuccess(() -> AuthTranslations.componentForSource(
                    source,
                    "auth.command.remove.success",
                    target,
                    resolvedUuid), true);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.remove.failure",
                    CommandSupport.describeCommandFailure(source, "removing all player data", runtimeException)));
            return 0;
        }
    }

}
